package com.example.concure.prediction;

import com.example.concure.data.SensorReading;
import java.util.List;

/**
 * ASTM C1074 Maturity Method Calculator
 * Implements the maturity index calculation for concrete curing prediction
 * 
 * Maturity Index Formula: M = Σ (Tavg - T0) * Δt
 * Where:
 * - M = Maturity Index (degree-hours)
 * - Tavg = Average temperature during time interval
 * - T0 = Reference temperature (0°C for ASTM C1074)
 * - Δt = Time interval
 * 
 * Note: ASTM C1074 requires mix-specific calibration for accurate results.
 * The values below are industry averages and should be calibrated for specific mixes.
 */
public class MaturityCalculator {
    
    private static final float REFERENCE_TEMPERATURE = 10.0f; // T0 = 10°C (more realistic datum temperature)
    
    // Realistic maturity targets (degree-hours) based on ASTM C1074 standards
    // These values reflect actual concrete curing requirements for 7-28 day periods
    private static final float TARGET_MATURITY_7_DAY = 1800.0f;   // 7-day equivalent strength
    private static final float TARGET_MATURITY_14_DAY = 2800.0f;  // 14-day equivalent strength  
    private static final float TARGET_MATURITY_28_DAY = 3800.0f;  // 28-day equivalent strength
    
    // Default target (28-day equivalent)
    private static final float DEFAULT_TARGET_MATURITY = TARGET_MATURITY_28_DAY;
    
    /**
     * Calculate cumulative maturity index from sensor readings
     * @param readings List of sensor readings in chronological order
     * @return Cumulative maturity index
     */
    public static float calculateMaturity(List<SensorReading> readings) {
        if (readings == null || readings.size() < 2) {
            return 0.0f;
        }
        
        float totalMaturity = 0.0f;
        
        for (int i = 1; i < readings.size(); i++) {
            SensorReading current = readings.get(i);
            SensorReading previous = readings.get(i - 1);
            
            // Calculate time interval in hours
            long timeDiff = current.getTimestamp() - previous.getTimestamp();
            float deltaTimeHours = timeDiff / (1000.0f * 60.0f * 60.0f); // Convert ms to hours
            
            // Calculate average temperature
            float avgTemperature = (current.getTemperature() + previous.getTemperature()) / 2.0f;
            
            // Calculate maturity contribution: (Tavg - T0) * Δt
            float maturityContribution = (avgTemperature - REFERENCE_TEMPERATURE) * deltaTimeHours;
            
            // Only add positive contributions (temperatures above 0°C)
            if (maturityContribution > 0) {
                totalMaturity += maturityContribution;
            }
        }
        
        return totalMaturity;
    }
    
    /**
     * Calculate maturity from two consecutive readings
     * @param previous Previous reading
     * @param current Current reading
     * @return Maturity contribution
     */
    public static float calculateMaturityIncrement(SensorReading previous, SensorReading current) {
        if (previous == null || current == null) {
            return 0.0f;
        }
        
        // Calculate time interval in hours
        long timeDiff = current.getTimestamp() - previous.getTimestamp();
        float deltaTimeHours = timeDiff / (1000.0f * 60.0f * 60.0f);
        
        // Calculate average temperature
        float avgTemperature = (current.getTemperature() + previous.getTemperature()) / 2.0f;
        
        // Calculate maturity contribution
        float maturityContribution = (avgTemperature - REFERENCE_TEMPERATURE) * deltaTimeHours;
        
        // Only return positive contributions (temperatures above datum temperature)
        return Math.max(0.0f, maturityContribution);
    }
    
    /**
     * Calculate percentage of curing completion
     * @param currentMaturity Current maturity index
     * @param targetMaturity Target maturity index (default: 28-day equivalent)
     * @return Completion percentage (0-100)
     */
    public static float calculateCompletionPercentage(float currentMaturity, float targetMaturity) {
        if (targetMaturity <= 0) return 0.0f;
        return Math.min(100.0f, (currentMaturity / targetMaturity) * 100.0f);
    }
    
    /**
     * Calculate completion percentage with default 28-day target
     * @param currentMaturity Current maturity index
     * @return Completion percentage (0-100)
     */
    public static float calculateCompletionPercentage(float currentMaturity) {
        return calculateCompletionPercentage(currentMaturity, DEFAULT_TARGET_MATURITY);
    }
    
    /**
     * Calculate completion percentage for 7-day equivalent strength
     * @param currentMaturity Current maturity index
     * @return Completion percentage (0-100)
     */
    public static float calculate7DayCompletionPercentage(float currentMaturity) {
        return calculateCompletionPercentage(currentMaturity, TARGET_MATURITY_7_DAY);
    }
    
    /**
     * Calculate completion percentage for 14-day equivalent strength
     * @param currentMaturity Current maturity index
     * @return Completion percentage (0-100)
     */
    public static float calculate14DayCompletionPercentage(float currentMaturity) {
        return calculateCompletionPercentage(currentMaturity, TARGET_MATURITY_14_DAY);
    }
    
    /**
     * Calculate completion percentage for 28-day equivalent strength
     * @param currentMaturity Current maturity index
     * @return Completion percentage (0-100)
     */
    public static float calculate28DayCompletionPercentage(float currentMaturity) {
        return calculateCompletionPercentage(currentMaturity, TARGET_MATURITY_28_DAY);
    }
    
    /**
     * Estimate time to completion based on current maturity rate
     * @param currentMaturity Current maturity index
     * @param targetMaturity Target maturity index
     * @param recentReadings Recent readings to calculate rate
     * @return Estimated hours to completion
     */
    public static long estimateTimeToCompletion(float currentMaturity, float targetMaturity, List<SensorReading> recentReadings) {
        if (recentReadings == null || recentReadings.size() < 2) {
            return -1; // Cannot estimate without recent data
        }
        
        // Calculate recent maturity rate (maturity per hour)
        float recentMaturity = calculateMaturity(recentReadings);
        long timeSpan = recentReadings.get(recentReadings.size() - 1).getTimestamp() - recentReadings.get(0).getTimestamp();
        float timeSpanHours = timeSpan / (1000.0f * 60.0f * 60.0f);
        
        if (timeSpanHours <= 0 || recentMaturity <= 0) {
            return -1;
        }
        
        float maturityRate = recentMaturity / timeSpanHours; // Maturity per hour
        float remainingMaturity = targetMaturity - currentMaturity;
        
        if (maturityRate <= 0) {
            return -1;
        }
        
        return (long) (remainingMaturity / maturityRate);
    }
    
    /**
     * Estimate completion time based on average temperature
     * @param currentMaturity Current maturity index
     * @param targetMaturity Target maturity index
     * @param averageTemperature Average curing temperature
     * @return Estimated hours to completion
     */
    public static long estimateTimeToCompletion(float currentMaturity, float targetMaturity, float averageTemperature) {
        if (averageTemperature <= REFERENCE_TEMPERATURE) {
            return -1; // No curing below datum temperature
        }
        
        float maturityRate = averageTemperature - REFERENCE_TEMPERATURE; // Maturity per hour
        float remainingMaturity = targetMaturity - currentMaturity;
        
        if (maturityRate <= 0) {
            return -1;
        }
        
        return (long) (remainingMaturity / maturityRate);
    }
    
    /**
     * Get realistic completion time estimate in days
     * @param currentMaturity Current maturity index
     * @param targetMaturity Target maturity index
     * @param averageTemperature Average curing temperature
     * @return Estimated days to completion
     */
    public static float estimateDaysToCompletion(float currentMaturity, float targetMaturity, float averageTemperature) {
        long hoursToCompletion = estimateTimeToCompletion(currentMaturity, targetMaturity, averageTemperature);
        if (hoursToCompletion <= 0) {
            return -1;
        }
        return hoursToCompletion / 24.0f;
    }
    
    /**
     * Get the default target maturity for 28-day equivalent strength
     * @return Target maturity index
     */
    public static float getDefaultTargetMaturity() {
        return DEFAULT_TARGET_MATURITY;
    }
    
    /**
     * Get target maturity for 7-day equivalent strength
     * @return Target maturity index
     */
    public static float get7DayTargetMaturity() {
        return TARGET_MATURITY_7_DAY;
    }
    
    /**
     * Get target maturity for 14-day equivalent strength
     * @return Target maturity index
     */
    public static float get14DayTargetMaturity() {
        return TARGET_MATURITY_14_DAY;
    }
    
    /**
     * Get target maturity for 28-day equivalent strength
     * @return Target maturity index
     */
    public static float get28DayTargetMaturity() {
        return TARGET_MATURITY_28_DAY;
    }
    
    /**
     * Get the reference temperature used in calculations
     * @return Reference temperature in Celsius
     */
    public static float getReferenceTemperature() {
        return REFERENCE_TEMPERATURE;
    }
    
    /**
     * Determine curing stage based on maturity index
     * @param currentMaturity Current maturity index
     * @return Curing stage description
     */
    public static String getCuringStage(float currentMaturity) {
        float progress7Day = calculate7DayCompletionPercentage(currentMaturity);
        float progress14Day = calculate14DayCompletionPercentage(currentMaturity);
        float progress28Day = calculate28DayCompletionPercentage(currentMaturity);
        
        if (progress28Day >= 100.0f) {
            return "Fully Cured (28-day strength achieved)";
        } else if (progress14Day >= 100.0f) {
            return "Well Cured (14-day strength achieved)";
        } else if (progress7Day >= 100.0f) {
            return "Initial Curing Complete (7-day strength achieved)";
        } else if (progress7Day >= 50.0f) {
            return "Active Curing (7-day: " + String.format("%.1f", progress7Day) + "%)";
        } else {
            return "Early Curing (7-day: " + String.format("%.1f", progress7Day) + "%)";
        }
    }
    
    /**
     * Check if concrete has reached specified strength level
     * @param currentMaturity Current maturity index
     * @param targetDays Target curing period (7, 14, or 28 days)
     * @return true if target strength is achieved
     */
    public static boolean hasReachedTargetStrength(float currentMaturity, int targetDays) {
        switch (targetDays) {
            case 7:
                return calculate7DayCompletionPercentage(currentMaturity) >= 100.0f;
            case 14:
                return calculate14DayCompletionPercentage(currentMaturity) >= 100.0f;
            case 28:
                return calculate28DayCompletionPercentage(currentMaturity) >= 100.0f;
            default:
                return calculateCompletionPercentage(currentMaturity) >= 100.0f;
        }
    }
}
