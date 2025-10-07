package com.example.concure.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Delete;
import java.util.List;

/**
 * Data Access Object for sensor readings
 * Provides methods for database operations on sensor data
 */
@Dao
public interface SensorReadingDao {
    
    @Insert
    void insert(SensorReading reading);
    
    @Query("SELECT * FROM sensor_readings ORDER BY timestamp DESC LIMIT :limit")
    List<SensorReading> getRecentReadings(int limit);
    
    @Query("SELECT * FROM sensor_readings WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp ASC")
    List<SensorReading> getReadingsInRange(long startTime, long endTime);
    
    @Query("SELECT * FROM sensor_readings WHERE isCuringActive = 1 ORDER BY timestamp ASC")
    List<SensorReading> getCuringReadings();
    
    @Query("SELECT * FROM sensor_readings ORDER BY timestamp DESC")
    List<SensorReading> getAllReadings();
    
    @Query("DELETE FROM sensor_readings WHERE timestamp < :cutoffTime")
    void deleteOldReadings(long cutoffTime);
    
    @Delete
    void delete(SensorReading reading);
}
