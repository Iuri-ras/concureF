package com.example.concure.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Entity class for storing sensor readings (temperature and humidity)
 * Used for real-time data logging and historical analysis
 */
@Entity(tableName = "sensor_readings")
public class SensorReading {
    @PrimaryKey(autoGenerate = true)
    public long id;
    
    public long timestamp;
    public float temperature;
    public float humidity;
    public boolean isCuringActive;
    
    public SensorReading() {
        this.timestamp = System.currentTimeMillis();
    }
    
    public SensorReading(float temperature, float humidity, boolean isCuringActive) {
        this.timestamp = System.currentTimeMillis();
        this.temperature = temperature;
        this.humidity = humidity;
        this.isCuringActive = isCuringActive;
    }
    
    // Getters and setters
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    
    public float getTemperature() { return temperature; }
    public void setTemperature(float temperature) { this.temperature = temperature; }
    
    public float getHumidity() { return humidity; }
    public void setHumidity(float humidity) { this.humidity = humidity; }
    
    public boolean isCuringActive() { return isCuringActive; }
    public void setCuringActive(boolean curingActive) { isCuringActive = curingActive; }
}
