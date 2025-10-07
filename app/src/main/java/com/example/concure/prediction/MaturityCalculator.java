package com.example.concure.prediction;

import com.example.concure.data.SensorReading;
import java.util.List;

/**
 * ASTM C1074 Maturity Method Calculator
 * Implements the maturity index calculation for concrete curing prediction
 * 
 * Maturity Index Formula: M = Σ (Tavg - T0) * Δt
 * Where:
 * - M = Maturity Index
 * - Tavg = Average temperature during time interval
 * - T0 = Reference temperature (0°C for ASTM C1074)
 * - Δt = Time interval
 */
public class MaturityCalculator {
    
    private static final float REFERENCE_TEMPERATURE = 0.0f; // T0 = 0°C (ASTM C1074)
    private static final float TARGET_MATURITY_28_DAY = 1000.0f; // Typical target for 28-day equivalent
    
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
        
        // Only return positive contributions
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
     * Get the default target maturity for 28-day equivalent strength
     * @return Target maturity index
     */
    public static float getDefaultTargetMaturity() {
        return TARGET_MATURITY_28_DAY;
    }
    
    /**
     * Get the reference temperature used in calculations
     * @return Reference temperature in Celsius
     */
    public static float getReferenceTemperature() {
        return REFERENCE_TEMPERATURE;
    }
}
