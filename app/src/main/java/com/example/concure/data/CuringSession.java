package com.example.concure.data;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;
import com.example.concure.prediction.MaturityCalculator;

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
        this.targetMaturity = MaturityCalculator.getDefaultTargetMaturity(); // Updated to use new default
    }
    
    @Ignore
    public CuringSession(long startTimestamp, float targetMaturity) {
        this.startTimestamp = startTimestamp;
        this.targetMaturity = targetMaturity;
        this.isActive = true;
        this.currentMaturity = 0.0f;
    }
    
    @Ignore
    public CuringSession(long startTimestamp, int targetDays) {
        this.startTimestamp = startTimestamp;
        this.isActive = true;
        this.currentMaturity = 0.0f;
        
        // Set target maturity based on curing period
        switch (targetDays) {
            case 7:
                this.targetMaturity = MaturityCalculator.get7DayTargetMaturity();
                break;
            case 14:
                this.targetMaturity = MaturityCalculator.get14DayTargetMaturity();
                break;
            case 28:
                this.targetMaturity = MaturityCalculator.get28DayTargetMaturity();
                break;
            default:
                this.targetMaturity = MaturityCalculator.getDefaultTargetMaturity();
                break;
        }
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
     * Calculate the percentage of curing completion based on strength achievement
     * @return Percentage (0-100)
     */
    public float getCompletionPercentage() {
        return MaturityCalculator.calculateCuringPercentage(currentMaturity);
    }
    
    /**
     * Calculate the percentage of curing completion for a specific target strength
     * @param targetStrength Target strength in MPa
     * @return Percentage (0-100)
     */
    public float getCompletionPercentage(float targetStrength) {
        return MaturityCalculator.calculateCuringPercentage(currentMaturity, targetStrength);
    }
    
    /**
     * Get current estimated strength based on maturity
     * @return Estimated strength in MPa
     */
    public float getCurrentStrength() {
        return MaturityCalculator.calculateStrengthFromMaturity(currentMaturity);
    }
    
    /**
     * Get the curing stage description based on current maturity
     * @return Curing stage description
     */
    public String getCuringStage() {
        return MaturityCalculator.getCuringStage(currentMaturity);
    }
    
    /**
     * Check if the session has reached 7-day equivalent strength
     * @return true if 7-day strength is achieved
     */
    public boolean hasReached7DayStrength() {
        return MaturityCalculator.hasReachedTargetStrength(currentMaturity, 7);
    }
    
    /**
     * Check if the session has reached 14-day equivalent strength
     * @return true if 14-day strength is achieved
     */
    public boolean hasReached14DayStrength() {
        return MaturityCalculator.hasReachedTargetStrength(currentMaturity, 14);
    }
    
    /**
     * Check if the session has reached 28-day equivalent strength
     * @return true if 28-day strength is achieved
     */
    public boolean hasReached28DayStrength() {
        return MaturityCalculator.hasReachedTargetStrength(currentMaturity, 28);
    }
    
    /**
     * Get the target curing period in days based on target maturity
     * @return Target curing period (7, 14, or 28 days)
     */
    public int getTargetCuringPeriod() {
        if (Math.abs(targetMaturity - MaturityCalculator.get7DayTargetMaturity()) < 50.0f) {
            return 7;
        } else if (Math.abs(targetMaturity - MaturityCalculator.get14DayTargetMaturity()) < 50.0f) {
            return 14;
        } else if (Math.abs(targetMaturity - MaturityCalculator.get28DayTargetMaturity()) < 50.0f) {
            return 28;
        } else {
            return 28; // Default to 28-day
        }
    }
}
