/*
 * Copyright 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package de.dennisguse.opentracks.stats;

import android.util.Log;

import androidx.annotation.NonNull;

import java.time.Duration;
import java.util.List;

import de.dennisguse.opentracks.data.models.Distance;
import de.dennisguse.opentracks.data.models.HeartRate;
import de.dennisguse.opentracks.data.models.Speed;
import de.dennisguse.opentracks.data.models.TrackPoint;
import de.dennisguse.opentracks.data.models.UnitConversions;

/**
 * Updater for {@link TrackStatistics}.
 * For updating track {@link TrackStatistics} as new {@link TrackPoint}s are added.
 * NOTE: Some of the locations represent pause/resume separator.
 * NOTE: Has still support for segments (at the moment unused).
 *
 * @author Sandor Dornbush
 * @author Rodrigo Damazio
 */
public class TrackStatisticsUpdater {

    private static final String TAG = TrackStatisticsUpdater.class.getSimpleName();
    /**
     * Ignore any acceleration faster than this.
     * Will ignore any speeds that imply acceleration greater than 2g's
     */
    private static final double SPEED_MAX_ACCELERATION = 2 * 9.81;

    private final TrackStatistics trackStatistics;

    private float averageHeartRateBPM;
    private Duration totalHeartRateDuration = Duration.ZERO;

    // The current segment's statistics
    private final TrackStatistics currentSegment;
    // Current segment's last trackPoint
    private TrackPoint lastTrackPoint;

    public TrackStatisticsUpdater() {
        this(new TrackStatistics());
    }

    /**
     * Creates a new{@link TrackStatisticsUpdater} with a {@link TrackStatisticsUpdater} already existed.
     *
     * @param trackStatistics a {@link TrackStatisticsUpdater}
     */
    public TrackStatisticsUpdater(TrackStatistics trackStatistics) {
        this.trackStatistics = trackStatistics;
        this.currentSegment = new TrackStatistics();

        resetAverageHeartRate();
    }

    public TrackStatisticsUpdater(TrackStatisticsUpdater toCopy) {
        this.currentSegment = new TrackStatistics(toCopy.currentSegment);
        this.trackStatistics = new TrackStatistics(toCopy.trackStatistics);

        this.lastTrackPoint = toCopy.lastTrackPoint;
        resetAverageHeartRate();
    }

    public TrackStatistics getTrackStatistics() {
        // Take a snapshot - we don't want anyone messing with our trackStatistics
        TrackStatistics stats = new TrackStatistics(trackStatistics);
        stats.merge(currentSegment);
        return stats;
    }

    public void addTrackPoints(List<TrackPoint> trackPoints) {
        trackPoints.stream().forEachOrdered(this::addTrackPoint);
    }

    /**
     *
     */
    public void addTrackPoint(TrackPoint trackPoint) {
        if (trackPoint.isSegmentStart()) {
            reset(trackPoint);
        }

        if (!currentSegment.isInitialized()) {
            currentSegment.setStartTime(trackPoint.getTime());
        }

        // Always update time
        currentSegment.setStopTime(trackPoint.getTime());
        currentSegment.setTotalTime(Duration.between(currentSegment.getStartTime(), trackPoint.getTime()));

        // Process sensor data: barometer
        if (trackPoint.hasAltitudeGain()) {
            currentSegment.addTotalAltitudeGain(trackPoint.getAltitudeGain());
        }

        if (trackPoint.hasAltitudeLoss()) {
            currentSegment.addTotalAltitudeLoss(trackPoint.getAltitudeLoss());
        }

        //Update absolute (GPS-based) altitude
        if (trackPoint.hasAltitude()) {
            currentSegment.updateAltitudeExtremities(trackPoint.getAltitude());
        }

        // Update heart rate
        if (trackPoint.hasHeartRate() && lastTrackPoint != null) {
            Duration trackPointDuration = Duration.between(lastTrackPoint.getTime(), trackPoint.getTime());
            Duration newTotalDuration = totalHeartRateDuration.plus(trackPointDuration);

            averageHeartRateBPM = (totalHeartRateDuration.toMillis() * averageHeartRateBPM + trackPointDuration.toMillis() * trackPoint.getHeartRate().getBPM()) / newTotalDuration.toMillis();
            totalHeartRateDuration = newTotalDuration;

            currentSegment.setAverageHeartRate(HeartRate.of(averageHeartRateBPM));
        }

        // Update total distance
        if (trackPoint.hasSensorDistance()) {
            // Sensor-based distance/speed
            currentSegment.addTotalDistance(trackPoint.getSensorDistance());
        } else if (lastTrackPoint != null
                && lastTrackPoint.hasLocation()
                && trackPoint.hasLocation() && trackPoint.isMoving()) {
            // GPS-based distance/speed
            // Assumption: we ignore TrackPoints that are not moving as those are likely imprecise GPS measurements
            Distance movingDistance = trackPoint.distanceToPrevious(lastTrackPoint);
            currentSegment.addTotalDistance(movingDistance);
        }


        // Update moving time
        if (trackPoint.isMoving() && lastTrackPoint != null && lastTrackPoint.isMoving()) {
            currentSegment.addMovingTime(trackPoint, lastTrackPoint);

            // Update max speed
            updateSpeed(trackPoint, lastTrackPoint);
        }

        if (trackPoint.isSegmentEnd()) {
            reset(trackPoint);
            return;
        }

        lastTrackPoint = trackPoint;
    }

    private void reset(TrackPoint trackPoint) {
        if (currentSegment.isInitialized()) {
            trackStatistics.merge(currentSegment);
        }
        currentSegment.reset(trackPoint.getTime());

        lastTrackPoint = null;
        resetAverageHeartRate();
    }

    private void resetAverageHeartRate() {
        averageHeartRateBPM = 0.0f;
        totalHeartRateDuration = Duration.ZERO;
    }

    /**
     * Updates a speed reading while assuming the user is moving.
     */
    private void updateSpeed(@NonNull TrackPoint trackPoint, @NonNull TrackPoint lastTrackPoint) {
        if (isValidSpeed(trackPoint, lastTrackPoint)) {
            Speed currentSpeed = trackPoint.getSpeed();
            if (currentSpeed.greaterThan(currentSegment.getMaxSpeed())) {
                currentSegment.setMaxSpeed(currentSpeed);
            }
        } else {
            Log.d(TAG, "Invalid speed. speed: " + trackPoint.getSpeed() + " lastLocationSpeed: " + lastTrackPoint.getSpeed());
        }
    }

    private boolean isValidSpeed(@NonNull TrackPoint trackPoint, @NonNull TrackPoint lastTrackPoint) {
        // See if the speed seems physically likely. Ignore any speeds that imply acceleration greater than 2g.
        Duration timeDifference = Duration.between(lastTrackPoint.getTime(), trackPoint.getTime());
        Speed maxSpeedDifference = Speed.of(Distance.of(SPEED_MAX_ACCELERATION), Duration.ofMillis(1000))
                .mul(timeDifference.toMillis() / UnitConversions.S_TO_MS);

        Speed speedDifference = Speed.absDiff(lastTrackPoint.getSpeed(), trackPoint.getSpeed());
        return speedDifference.lessThan(maxSpeedDifference);
    }

    @NonNull
    @Override
    public String toString() {
        return "TrackStatisticsUpdater{" +
                "trackStatistics=" + trackStatistics +
                '}';
    }
}
