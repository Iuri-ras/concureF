package com.example.concure;

import android.animation.ValueAnimator;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.concure.data.AppDatabase;
import com.example.concure.data.CuringSession;
import com.example.concure.data.CuringSessionDao;
import com.example.concure.data.SensorReading;
import com.example.concure.data.SensorReadingDao;
import com.example.concure.databinding.ActivityDashboardBinding;
import com.example.concure.prediction.MaturityCalculator;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DashboardActivity - Modern real-time concrete curing monitoring dashboard
 * Features:
 * - Real-time temperature and humidity display
 * - ASTM C1074 Maturity Method calculations
 * - Interactive charts with MPAndroidChart
 * - Progress tracking and completion predictions
 * - Material Design 3 UI with animations
 * - Synchronized BLE connection and data updates
 */
public class DashboardActivity extends AppCompatActivity implements 
        BleManager.OnDeviceConnectedListener,
        BleManager.OnDeviceDisconnectedListener,
        BleManager.OnDataReceivedListener,
        BleManager.OnConnectionStatusListener {

    private static final String TAG = "DashboardActivity";
    private static final String CHANNEL_ID = "curing_notifications";
    private static final int NOTIFICATION_ID = 1;
    
    // ViewBinding
    private ActivityDashboardBinding binding;
    
    // BLE Manager
    private BleManager bleManager;
    
    // Database
    private AppDatabase database;
    private SensorReadingDao sensorReadingDao;
    private CuringSessionDao curingSessionDao;
    
    // Current data
    private SensorReading lastReading;
    private CuringSession activeSession;
    private List<SensorReading> chartData;
    
    // Chart data
    private LineDataSet temperatureDataSet;
    private LineDataSet humidityDataSet;
    private LineData lineData;
    
    // Threading
    private ExecutorService executor;
    private Handler mainHandler;
    
    // Animation
    private ValueAnimator progressAnimator;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Initialize ViewBinding
        binding = ActivityDashboardBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        
        // Initialize components
        initializeComponents();
        setupUI();
        setupChart();
        setupClickListeners();
        createNotificationChannel();
        
        // Load existing data
        loadExistingData();
    }
    
    /**
     * Initialize all components and services
     */
    private void initializeComponents() {
        // BLE Manager with enhanced callbacks (singleton instance)
        bleManager = BleManager.getInstance(this);
        bleManager.setOnDeviceConnectedListener(this);
        bleManager.setOnDeviceDisconnectedListener(this);
        bleManager.setOnDataReceivedListener(this);
        bleManager.setOnConnectionStatusListener(this);
        
        // Database
        database = AppDatabase.getDatabase(this);
        sensorReadingDao = database.sensorReadingDao();
        curingSessionDao = database.curingSessionDao();
        
        // Threading
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        
        // Data
        chartData = new ArrayList<>();
    }
    
    /**
     * Setup UI components and initial state
     */
    private void setupUI() {
        // Set initial connection status based on BLE manager state
        boolean isConnected = bleManager.isConnected();
        String deviceName = bleManager.getConnectedDeviceName();
        String status = isConnected ? "Connected to " + deviceName : "Disconnected";
        updateConnectionStatus(isConnected, status);
        
        // Initialize temperature and humidity displays
        updateTemperatureDisplay(null);
        updateHumidityDisplay(null);
        
        // Setup bottom navigation
        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_dashboard) {
                // Already on dashboard
                return true;
            } else if (itemId == R.id.nav_ble_settings) {
                // Navigate to BLE settings (MainActivity)
                Intent intent = new Intent(this, MainActivity.class);
                startActivity(intent);
                return true;
            } else if (itemId == R.id.nav_history) {
                // TODO: Navigate to history
                Toast.makeText(this, "History feature coming soon", Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });
    }
    
    /**
     * Setup the real-time chart
     */
    private void setupChart() {
        LineChart chart = binding.trendChart;
        
        // Configure chart appearance
        chart.setBackgroundColor(Color.WHITE);
        chart.getDescription().setEnabled(false);
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(true);
        chart.setPinchZoom(true);
        chart.setDrawGridBackground(false);
        
        // Configure X-axis
        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setValueFormatter(new TimeValueFormatter());
        
        // Configure Y-axis
        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setAxisMinimum(0f);
        leftAxis.setAxisMaximum(100f);
        
        YAxis rightAxis = chart.getAxisRight();
        rightAxis.setEnabled(false);
        
        // Create data sets
        temperatureDataSet = new LineDataSet(null, "Temperature (°C)");
        temperatureDataSet.setColor(Color.parseColor("#1976D2"));
        temperatureDataSet.setCircleColor(Color.parseColor("#1976D2"));
        temperatureDataSet.setLineWidth(3f);
        temperatureDataSet.setCircleRadius(4f);
        temperatureDataSet.setDrawValues(false);
        temperatureDataSet.setAxisDependency(YAxis.AxisDependency.LEFT);
        
        humidityDataSet = new LineDataSet(null, "Humidity (%)");
        humidityDataSet.setColor(Color.parseColor("#00796B"));
        humidityDataSet.setCircleColor(Color.parseColor("#00796B"));
        humidityDataSet.setLineWidth(3f);
        humidityDataSet.setCircleRadius(4f);
        humidityDataSet.setDrawValues(false);
        humidityDataSet.setAxisDependency(YAxis.AxisDependency.LEFT);
        
        // Create line data
        lineData = new LineData();
        lineData.addDataSet(temperatureDataSet);
        lineData.addDataSet(humidityDataSet);
        chart.setData(lineData);
        
        chart.invalidate();
    }
    
    /**
     * Setup click listeners for buttons
     */
    private void setupClickListeners() {
        // Connect button
        binding.connectButton.setOnClickListener(v -> {
            if (bleManager.isConnected()) {
                bleManager.disconnect();
            } else {
                // Navigate to main activity for device selection
                Intent intent = new Intent(this, MainActivity.class);
                startActivity(intent);
            }
        });
        
        // Start curing button
        binding.startCuringButton.setOnClickListener(v -> startCuringProcess());
        
        // Stop curing button
        binding.stopCuringButton.setOnClickListener(v -> stopCuringProcess());
    }
    
    /**
     * Load existing data from database
     */
    private void loadExistingData() {
        executor.execute(() -> {
            try {
                // Load active curing session
                CuringSession session = curingSessionDao.getActiveSession();
                if (session != null) {
                    mainHandler.post(() -> {
                        activeSession = session;
                        updateCuringUI();
                    });
                }
                
                // Load recent sensor readings
                List<SensorReading> readings = sensorReadingDao.getRecentReadings(100);
                mainHandler.post(() -> {
                    chartData.clear();
                    chartData.addAll(readings);
                    updateChart();
                });
                
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
    
    /**
     * Update connection status UI
     */
    private void updateConnectionStatus(boolean connected, String status) {
        if (connected) {
            binding.connectionStatusIcon.setImageResource(R.drawable.ic_bluetooth_connected);
            binding.connectionStatusIcon.setColorFilter(getColor(R.color.success_color));
            binding.connectionStatusText.setText("Connected");
            binding.connectionStatusSubtext.setText("Receiving live data from ESP32");
            binding.connectButton.setText("Disconnect");
        } else {
            binding.connectionStatusIcon.setImageResource(R.drawable.ic_bluetooth_disabled);
            binding.connectionStatusIcon.setColorFilter(getColor(R.color.error_color));
            binding.connectionStatusText.setText("Disconnected");
            binding.connectionStatusSubtext.setText("Connect to ESP32 to start monitoring");
            binding.connectButton.setText("Connect");
        }
    }
    
    /**
     * Update temperature display with animation
     */
    private void updateTemperatureDisplay(SensorReading reading) {
        if (reading == null) {
            binding.temperatureValue.setText("--°C");
            binding.temperatureStatus.setText("No data");
            return;
        }
        
        float temperature = reading.getTemperature();
        
        // Animate temperature value
        animateValue(binding.temperatureValue, temperature, "°C");
        
        // Update status based on temperature
        String status;
        if (temperature < 5) {
            status = "Too cold";
        } else if (temperature > 35) {
            status = "Too hot";
        } else {
            status = "Optimal";
        }
        binding.temperatureStatus.setText(status);
    }
    
    /**
     * Update humidity display with animation
     */
    private void updateHumidityDisplay(SensorReading reading) {
        if (reading == null) {
            binding.humidityValue.setText("--%");
            binding.humidityStatus.setText("No data");
            return;
        }
        
        float humidity = reading.getHumidity();
        
        // Animate humidity value
        animateValue(binding.humidityValue, humidity, "%");
        
        // Update status based on humidity
        String status;
        if (humidity < 40) {
            status = "Low humidity";
        } else if (humidity > 80) {
            status = "High humidity";
        } else {
            status = "Optimal";
        }
        binding.humidityStatus.setText(status);
    }
    
    /**
     * Animate numeric value changes
     */
    private void animateValue(android.widget.TextView textView, float targetValue, String suffix) {
        String currentText = textView.getText().toString();
        float currentValue = 0;
        
        try {
            currentValue = Float.parseFloat(currentText.replaceAll("[^\\d.-]", ""));
        } catch (NumberFormatException e) {
            // If parsing fails, start from 0
        }
        
        ValueAnimator animator = ValueAnimator.ofFloat(currentValue, targetValue);
        animator.setDuration(500);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            float value = (float) animation.getAnimatedValue();
            textView.setText(String.format(Locale.getDefault(), "%.1f%s", value, suffix));
        });
        animator.start();
    }
    
    /**
     * Update the real-time chart
     */
    private void updateChart() {
        if (chartData.isEmpty()) return;
        
        // Clear existing data
        temperatureDataSet.clear();
        humidityDataSet.clear();
        
        // Add new data points
        for (int i = 0; i < chartData.size(); i++) {
            SensorReading reading = chartData.get(i);
            float time = (reading.getTimestamp() - chartData.get(0).getTimestamp()) / (1000f * 60f); // Minutes
            
            temperatureDataSet.addEntry(new Entry(time, reading.getTemperature()));
            humidityDataSet.addEntry(new Entry(time, reading.getHumidity()));
        }
        
        // Notify chart of data change
        lineData.notifyDataChanged();
        binding.trendChart.notifyDataSetChanged();
        binding.trendChart.invalidate();
    }
    
    /**
     * Start the curing process
     */
    private void startCuringProcess() {
        executor.execute(() -> {
            try {
                // Create new curing session
                CuringSession session = new CuringSession();
                long sessionId = curingSessionDao.insert(session);
                session.setId(sessionId);
                
                mainHandler.post(() -> {
                    activeSession = session;
                    updateCuringUI();
                    Toast.makeText(this, "Curing process started", Toast.LENGTH_SHORT).show();
                });
                
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> 
                    Toast.makeText(this, "Failed to start curing process", Toast.LENGTH_SHORT).show());
            }
        });
    }
    
    /**
     * Stop the curing process
     */
    private void stopCuringProcess() {
        if (activeSession == null) return;
        
        executor.execute(() -> {
            try {
                activeSession.setActive(false);
                activeSession.setEndTimestamp(System.currentTimeMillis());
                curingSessionDao.update(activeSession);
                
                mainHandler.post(() -> {
                    activeSession = null;
                    updateCuringUI();
                    Toast.makeText(this, "Curing process stopped", Toast.LENGTH_SHORT).show();
                });
                
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> 
                    Toast.makeText(this, "Failed to stop curing process", Toast.LENGTH_SHORT).show());
            }
        });
    }
    
    /**
     * Update curing progress UI
     */
    private void updateCuringUI() {
        if (activeSession == null) {
            // Show start curing section
            binding.startCuringSection.setVisibility(View.VISIBLE);
            binding.progressSection.setVisibility(View.GONE);
            return;
        }
        
        // Show progress section
        binding.startCuringSection.setVisibility(View.GONE);
        binding.progressSection.setVisibility(View.VISIBLE);
        
        // Update progress circle
        float progress = activeSession.getCompletionPercentage();
        binding.curingProgressCircle.setProgress((int) progress);
        
        // Update progress text
        binding.curingProgressText.setText(String.format(Locale.getDefault(), "%.1f%%", progress));
        
        // Update maturity values
        binding.currentMaturityText.setText(String.format(Locale.getDefault(), "%.1f", activeSession.getCurrentMaturity()));
        binding.targetMaturityText.setText(String.format(Locale.getDefault(), "%.1f", activeSession.getTargetMaturity()));
        
        // Update completion date
        updateCompletionDate();
        
        // Check for 80% completion notification
        if (progress >= 80 && progress < 81) {
            showCompletionNotification();
        }
    }
    
    /**
     * Update estimated completion date
     */
    private void updateCompletionDate() {
        if (activeSession == null) return;
        
        // Calculate estimated completion time
        List<SensorReading> recentReadings = new ArrayList<>();
        for (int i = Math.max(0, chartData.size() - 10); i < chartData.size(); i++) {
            recentReadings.add(chartData.get(i));
        }
        
        long hoursToCompletion = MaturityCalculator.estimateTimeToCompletion(
            activeSession.getCurrentMaturity(),
            activeSession.getTargetMaturity(),
            recentReadings
        );
        
        if (hoursToCompletion > 0) {
            long completionTime = System.currentTimeMillis() + (hoursToCompletion * 60 * 60 * 1000);
            String dateStr = DateFormat.format("MMM dd, yyyy", new Date(completionTime)).toString();
            binding.completionDateText.setText(dateStr);
        } else {
            binding.completionDateText.setText("Calculating...");
        }
    }
    
    /**
     * Process new sensor data
     */
    private void processNewData(SensorReading reading) {
        // Only process if we have valid data
        if (reading == null) return;
        
        // Update timestamp if not set
        if (reading.getTimestamp() == 0) {
            reading.setTimestamp(System.currentTimeMillis());
        }
        
        lastReading = reading;
        
        // Update displays
        updateTemperatureDisplay(reading);
        updateHumidityDisplay(reading);
        
        // Add to chart data (avoid duplicates by checking timestamp)
        boolean isDuplicate = false;
        if (!chartData.isEmpty()) {
            SensorReading lastChartReading = chartData.get(chartData.size() - 1);
            // If timestamps are very close (within 1 second), consider it a duplicate
            if (Math.abs(reading.getTimestamp() - lastChartReading.getTimestamp()) < 1000) {
                isDuplicate = true;
                // Update the last reading instead of adding new one
                chartData.set(chartData.size() - 1, reading);
            }
        }
        
        if (!isDuplicate) {
            chartData.add(reading);
        }
        
        // Keep only last 100 points for performance
        if (chartData.size() > 100) {
            chartData.remove(0);
        }
        
        // Update chart
        updateChart();
        
        // Save to database
        executor.execute(() -> {
            try {
                sensorReadingDao.insert(reading);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        
        // Update curing progress if active
        if (activeSession != null) {
            updateCuringProgress(reading);
        }
    }
    
    /**
     * Update curing progress based on new reading
     */
    private void updateCuringProgress(SensorReading reading) {
        if (activeSession == null || chartData.size() < 2) return;
        
        executor.execute(() -> {
            try {
                // Calculate maturity increment
                SensorReading previous = chartData.get(chartData.size() - 2);
                float maturityIncrement = MaturityCalculator.calculateMaturityIncrement(previous, reading);
                
                // Update session maturity
                float newMaturity = activeSession.getCurrentMaturity() + maturityIncrement;
                activeSession.setCurrentMaturity(newMaturity);
                curingSessionDao.updateMaturity(activeSession.getId(), newMaturity);
                
                mainHandler.post(() -> updateCuringUI());
                
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
    
    /**
     * Show completion notification
     */
    private void showCompletionNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_thermometer)
                .setContentTitle("Curing Progress Update")
                .setContentText("Concrete has reached 80% maturity!")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }
    
    /**
     * Create notification channel for Android 8.0+
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Curing Notifications";
            String description = "Notifications for concrete curing progress";
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    /**
     * Custom value formatter for chart time axis
     */
    private class TimeValueFormatter extends ValueFormatter {
        @Override
        public String getFormattedValue(float value) {
            int minutes = (int) value;
            int hours = minutes / 60;
            minutes = minutes % 60;
            return String.format(Locale.getDefault(), "%d:%02d", hours, minutes);
        }
    }
    
    // Enhanced BLE Callback Implementations
    
    @Override
    public void onDeviceConnected(String deviceName, String deviceAddress) {
        runOnUiThread(() -> {
            Log.d(TAG, "Device connected: " + deviceName + " (" + deviceAddress + ")");
            updateConnectionStatus(true, "Connected to " + deviceName);
            Toast.makeText(this, "Connected to " + deviceName, Toast.LENGTH_SHORT).show();
        });
    }
    
    @Override
    public void onDeviceDisconnected() {
        runOnUiThread(() -> {
            Log.d(TAG, "Device disconnected");
            updateConnectionStatus(false, "Disconnected");
            Toast.makeText(this, "Disconnected from ESP32", Toast.LENGTH_SHORT).show();
        });
    }
    
    @Override
    public void onTemperatureReceived(float temperature) {
        runOnUiThread(() -> {
            Log.d(TAG, "Temperature received: " + temperature + "°C");
            
            // Create or update sensor reading
            SensorReading reading = lastReading != null ? lastReading : new SensorReading();
            reading.setTemperature(temperature);
            reading.setCuringActive(activeSession != null);
            
            processNewData(reading);
        });
    }
    
    @Override
    public void onHumidityReceived(float humidity) {
        runOnUiThread(() -> {
            Log.d(TAG, "Humidity received: " + humidity + "%");
            
            // Create or update sensor reading
            SensorReading reading = lastReading != null ? lastReading : new SensorReading();
            reading.setHumidity(humidity);
            reading.setCuringActive(activeSession != null);
            
            processNewData(reading);
        });
    }
    
    @Override
    public void onRawDataReceived(String data) {
        runOnUiThread(() -> {
            Log.d(TAG, "Raw data received: " + data);
            // Raw data is already parsed by the other callbacks
        });
    }
    
    @Override
    public void onConnectionStatusChanged(boolean isConnected, String status) {
        runOnUiThread(() -> {
            Log.d(TAG, "Connection status changed: " + isConnected + " - " + status);
            updateConnectionStatus(isConnected, status);
        });
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Update connection status when returning to dashboard
        boolean isConnected = bleManager.isConnected();
        String deviceName = bleManager.getConnectedDeviceName();
        String status = isConnected ? "Connected to " + deviceName : "Disconnected";
        updateConnectionStatus(isConnected, status);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdown();
        }
        // Don't cleanup BLE manager here as it might be used by other activities
    }
}
