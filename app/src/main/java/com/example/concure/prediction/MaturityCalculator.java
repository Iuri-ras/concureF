package com.example.concure.prediction;

import com.example.concure.data.SensorReading;
import java.util.List;

/**
 * ASTM C1074 Maturity Method Calculator
 * Implements the Nurse-Saul maturity function for concrete curing prediction
 * 
 * Nurse-Saul Maturity Formula: M(t) = Σ (Ti - T0) * Δti
 * Where:
 * - M(t) = Maturity Index (°C·h)
 * - Ti = Temperature at time i
 * - T0 = Datum temperature (0°C per ASTM C1074)
 * - Δti = Time interval
 * 
 * Strength Calibration: Strength(M) = a * ln(M) + b
 * Where a and b are mix-specific constants
 * 
 * Note: ASTM C1074 requires mix-specific calibration for accurate results.
 * The values below are industry averages and should be calibrated for specific mixes.
 */
public class MaturityCalculator {
    
    private static final float DATUM_TEMPERATURE = 0.0f; // T0 = 0°C (ASTM C1074 standard)
    
    // Strength calibration curve constants (default values, should be calibrated for specific mixes)
    // Strength(M) = a * ln(M) + b
    private static final float STRENGTH_CALIBRATION_A = 8.0f;  // Slope coefficient
    private static final float STRENGTH_CALIBRATION_B = -20.0f; // Intercept coefficient
    
    // Default target strength (MPa)
    private static final float DEFAULT_TARGET_STRENGTH = 35.0f; // 35 MPa (high strength)
    
    // Realistic ASTM-based target maturities (°C·h) for different strength levels
    private static final float TARGET_MATURITY_EARLY = 1500.0f;    // Early strength (10 MPa) - ~2 days @ 30°C
    private static final float TARGET_MATURITY_DESIGN = 5000.0f;   // Design strength (28 MPa) - ~7 days @ 30°C
    private static final float TARGET_MATURITY_HIGH = 7000.0f;     // High strength (35 MPa) - ~10 days @ 30°C
    private static final float TARGET_MATURITY_FULL = 8500.0f;     // Full strength (40 MPa) - ~12 days @ 30°C
    
    // Legacy targets for backward compatibility
    private static final float TARGET_MATURITY_7_DAY = TARGET_MATURITY_EARLY;   // Early strength
    private static final float TARGET_MATURITY_14_DAY = TARGET_MATURITY_DESIGN; // Design strength  
    private static final float TARGET_MATURITY_28_DAY = TARGET_MATURITY_FULL;   // Full strength
    
    // Default target (high strength - 35 MPa)
    private static final float DEFAULT_TARGET_MATURITY = TARGET_MATURITY_HIGH;
    
    /**
     * Calculate cumulative maturity index from sensor readings using proper time integration
     * @param readings List of sensor readings in chronological order
     * @return Cumulative maturity index in °C·h
     */
    public static float calculateMaturity(List<SensorReading> readings) {
        if (readings == null || readings.size() < 2) {
            return 0.0f;
        }
        
        float totalMaturity = 0.0f;
        
        for (int i = 1; i < readings.size(); i++) {
            SensorReading current = readings.get(i);
            SensorReading previous = readings.get(i - 1);
            
            // Calculate time interval in hours (proper time integration)
            long timeDiffMs = current.getTimestamp() - previous.getTimestamp();
            float deltaTimeHours = timeDiffMs / 3600000.0f; // Convert ms to hours (3600000 ms = 1 hour)
            
            // Calculate average temperature for the interval
            float avgTemperature = (current.getTemperature() + previous.getTemperature()) / 2.0f;
            
            // Nurse-Saul equation: M = Σ(max(0, T_concrete - T0) × Δt_hours)
            // Clamp negative contributions to 0
            float maturityContribution = Math.max(0.0f, (avgTemperature - DATUM_TEMPERATURE) * deltaTimeHours);
            totalMaturity += maturityContribution;
            
            // Log detailed calculation for debugging
            System.out.println(String.format("Δt_hours: %.4f, T_avg: %.1f°C, Maturity: %.2f °C·h, Total: %.2f °C·h", 
                deltaTimeHours, avgTemperature, maturityContribution, totalMaturity));
        }
        
        return totalMaturity;
    }
    
    /**
     * Calculate maturity increment from two consecutive readings
     * @param previous Previous reading
     * @param current Current reading
     * @return Maturity contribution in °C·h
     */
    public static float calculateMaturityIncrement(SensorReading previous, SensorReading current) {
        if (previous == null || current == null) {
            return 0.0f;
        }
        
        // Calculate time interval in hours (proper time integration)
        long timeDiffMs = current.getTimestamp() - previous.getTimestamp();
        float deltaTimeHours = timeDiffMs / 3600000.0f; // Convert ms to hours
        
        // Calculate average temperature for the interval
        float avgTemperature = (current.getTemperature() + previous.getTemperature()) / 2.0f;
        
        // Nurse-Saul equation: M = max(0, T_concrete - T0) × Δt_hours
        // Clamp negative contributions to 0
        float maturityContribution = Math.max(0.0f, (avgTemperature - DATUM_TEMPERATURE) * deltaTimeHours);
        
        return maturityContribution;
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
        if (averageTemperature <= DATUM_TEMPERATURE) {
            return -1; // No curing below datum temperature
        }
        
        float maturityRate = averageTemperature - DATUM_TEMPERATURE; // Maturity per hour
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
     * Get the datum temperature used in calculations (ASTM C1074 standard)
     * @return Datum temperature in Celsius
     */
    public static float getDatumTemperature() {
        return DATUM_TEMPERATURE;
    }
    
    /**
     * Calculate strength from maturity using calibration curve
     * Strength(M) = a * ln(M) + b
     * @param maturity Maturity index (°C·h)
     * @return Estimated strength (MPa)
     */
    public static float calculateStrengthFromMaturity(float maturity) {
        if (maturity <= 0) return 0.0f;
        float strength = STRENGTH_CALIBRATION_A * (float) Math.log(maturity) + STRENGTH_CALIBRATION_B;
        
        // Debug logging
        System.out.println(String.format("Strength calculation: Maturity=%.1f °C·h, ln(M)=%.3f, Strength=%.1f MPa", 
            maturity, Math.log(maturity), strength));
        
        return strength;
    }
    
    /**
     * Calculate maturity from target strength using inverse calibration curve
     * M = exp((Strength - b) / a)
     * @param targetStrength Target strength (MPa)
     * @return Required maturity (°C·h)
     */
    public static float calculateMaturityFromStrength(float targetStrength) {
        if (targetStrength <= 0) return 0.0f;
        return (float) Math.exp((targetStrength - STRENGTH_CALIBRATION_B) / STRENGTH_CALIBRATION_A);
    }
    
    /**
     * Calculate curing percentage based on strength achievement
     * @param currentMaturity Current maturity index (°C·h)
     * @param targetStrength Target strength (MPa)
     * @return Curing percentage (0-100)
     */
    public static float calculateCuringPercentage(float currentMaturity, float targetStrength) {
        if (targetStrength <= 0) return 0.0f;
        
        float currentStrength = calculateStrengthFromMaturity(currentMaturity);
        float curingPercent = Math.min(100.0f, (currentStrength / targetStrength) * 100.0f);
        
        return Math.round(curingPercent);
    }
    
    /**
     * Calculate curing percentage with default target strength
     * @param currentMaturity Current maturity index (°C·h)
     * @return Curing percentage (0-100)
     */
    public static float calculateCuringPercentage(float currentMaturity) {
        return calculateCuringPercentage(currentMaturity, DEFAULT_TARGET_STRENGTH);
    }
    
    /**
     * Get the default target strength
     * @return Target strength (MPa)
     */
    public static float getDefaultTargetStrength() {
        return DEFAULT_TARGET_STRENGTH;
    }
    
    /**
     * Calculate daily maturity gain at given average temperature
     * @param avgTemperature Average temperature in °C
     * @return Daily maturity gain in °C·h/day
     */
    public static float calculateDailyGain(float avgTemperature) {
        // Daily gain = max(0, T_avg - T0) × 24 hours
        return Math.max(0.0f, (avgTemperature - DATUM_TEMPERATURE) * 24.0f);
    }
    
    /**
     * Calculate days to reach target maturity at given average temperature
     * @param currentMaturity Current maturity in °C·h
     * @param targetMaturity Target maturity in °C·h
     * @param avgTemperature Average temperature in °C
     * @return Days to target (or -1 if temperature too low)
     */
    public static float calculateDaysToTarget(float currentMaturity, float targetMaturity, float avgTemperature) {
        float dailyGain = calculateDailyGain(avgTemperature);
        if (dailyGain <= 0) {
            return -1.0f; // Temperature too low for curing
        }
        
        float remainingMaturity = targetMaturity - currentMaturity;
        if (remainingMaturity <= 0) {
            return 0.0f; // Target already reached
        }
        
        return remainingMaturity / dailyGain;
    }
    
    /**
     * Calculate days to reach design strength (28 MPa) at given average temperature
     * @param currentMaturity Current maturity in °C·h
     * @param avgTemperature Average temperature in °C
     * @return Days to design strength
     */
    public static float calculateDaysToDesignStrength(float currentMaturity, float avgTemperature) {
        return calculateDaysToTarget(currentMaturity, TARGET_MATURITY_DESIGN, avgTemperature);
    }
    
    /**
     * Calculate days to reach full strength (40 MPa) at given average temperature
     * @param currentMaturity Current maturity in °C·h
     * @param avgTemperature Average temperature in °C
     * @return Days to full strength
     */
    public static float calculateDaysToFullStrength(float currentMaturity, float avgTemperature) {
        return calculateDaysToTarget(currentMaturity, TARGET_MATURITY_FULL, avgTemperature);
    }
    
    /**
     * Get target maturity for early strength (10 MPa)
     * @return Target maturity in °C·h
     */
    public static float getEarlyStrengthTarget() {
        return TARGET_MATURITY_EARLY;
    }
    
    /**
     * Get target maturity for design strength (28 MPa)
     * @return Target maturity in °C·h
     */
    public static float getDesignStrengthTarget() {
        return TARGET_MATURITY_DESIGN;
    }
    
    /**
     * Get target maturity for high strength (35 MPa)
     * @return Target maturity in °C·h
     */
    public static float getHighStrengthTarget() {
        return TARGET_MATURITY_HIGH;
    }
    
    /**
     * Get target maturity for full strength (40 MPa)
     * @return Target maturity in °C·h
     */
    public static float getFullStrengthTarget() {
        return TARGET_MATURITY_FULL;
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
