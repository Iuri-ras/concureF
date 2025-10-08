package com.example.concure.data;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/**
 * Entity class for storing curing sessions
 * Tracks start time, target maturity, and completion status
 */
@Entity(tableName = "curing_sessions")
public class CuringSession {
    @PrimaryKey(autoGenerate = true)
    public long id;
    
    public long startTimestamp;
    public long endTimestamp;
    public float targetMaturity; // Target maturity index for 28-day equivalent
    public float currentMaturity; // Current cumulative maturity
    public boolean isActive;
    public String notes;
    
    public CuringSession() {
        this.startTimestamp = System.currentTimeMillis();
        this.isActive = true;
        this.currentMaturity = 0.0f;
        this.targetMaturity = 1000.0f; // Typical target for 28-day equivalent
    }
    
    @Ignore
    public CuringSession(long startTimestamp, float targetMaturity) {
        this.startTimestamp = startTimestamp;
        this.targetMaturity = targetMaturity;
        this.isActive = true;
        this.currentMaturity = 0.0f;
    }
    
    // Getters and setters
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    
    public long getStartTimestamp() { return startTimestamp; }
    public void setStartTimestamp(long startTimestamp) { this.startTimestamp = startTimestamp; }
    
    public long getEndTimestamp() { return endTimestamp; }
    public void setEndTimestamp(long endTimestamp) { this.endTimestamp = endTimestamp; }
    
    public float getTargetMaturity() { return targetMaturity; }
    public void setTargetMaturity(float targetMaturity) { this.targetMaturity = targetMaturity; }
    
    public float getCurrentMaturity() { return currentMaturity; }
    public void setCurrentMaturity(float currentMaturity) { this.currentMaturity = currentMaturity; }
    
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    
    /**
     * Calculate the percentage of curing completion
     * @return Percentage (0-100)
     */
    public float getCompletionPercentage() {
        if (targetMaturity <= 0) return 0;
        return Math.min(100.0f, (currentMaturity / targetMaturity) * 100.0f);
    }
}
