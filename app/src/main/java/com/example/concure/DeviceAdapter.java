package com.example.concure;

import android.bluetooth.BluetoothDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for displaying discovered BLE devices in a RecyclerView
 */
public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder> {
    
    private List<BluetoothDevice> devices;
    private List<Integer> rssiValues;
    private OnDeviceClickListener deviceClickListener;
    private int selectedPosition = -1;
    
    public interface OnDeviceClickListener {
        void onDeviceClick(BluetoothDevice device, int position);
    }
    
    public DeviceAdapter() {
        this.devices = new ArrayList<>();
        this.rssiValues = new ArrayList<>();
    }
    
    public void setOnDeviceClickListener(OnDeviceClickListener listener) {
        this.deviceClickListener = listener;
    }
    
    public void addDevice(BluetoothDevice device, int rssi) {
        // Check if device already exists
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).getAddress().equals(device.getAddress())) {
                // Update RSSI for existing device
                rssiValues.set(i, rssi);
                notifyItemChanged(i);
                return;
            }
        }
        
        // Add new device (now shows ALL devices for debugging)
        devices.add(device);
        rssiValues.add(rssi);
        notifyItemInserted(devices.size() - 1);
    }
    
    public void clearDevices() {
        devices.clear();
        rssiValues.clear();
        selectedPosition = -1;
        notifyDataSetChanged();
    }
    
    public BluetoothDevice getSelectedDevice() {
        if (selectedPosition >= 0 && selectedPosition < devices.size()) {
            return devices.get(selectedPosition);
        }
        return null;
    }
    
    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(android.R.layout.simple_list_item_2, parent, false);
        return new DeviceViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        BluetoothDevice device = devices.get(position);
        int rssi = rssiValues.get(position);
        
        String deviceName;
        try {
            deviceName = device.getName();
            if (deviceName == null || deviceName.isEmpty()) {
                deviceName = "Unknown Device";
            }
        } catch (SecurityException e) {
            deviceName = "Unknown Device";
        }
        
        // Check if this is an ESP32 device
        boolean isEsp32 = deviceName != null && deviceName.toLowerCase().contains("esp32");
        
        String deviceInfo = deviceName + "\n" + device.getAddress();
        if (isEsp32) {
            deviceInfo = "🎯 " + deviceInfo; // Add target emoji for ESP32
        }
        holder.deviceName.setText(deviceInfo);
        
        // Display RSSI with signal strength indicator
        String rssiText = "RSSI: " + rssi + " dBm";
        if (rssi > -50) {
            rssiText += " (Excellent)";
        } else if (rssi > -70) {
            rssiText += " (Good)";
        } else if (rssi > -85) {
            rssiText += " (Fair)";
        } else {
            rssiText += " (Poor)";
        }
        
        if (isEsp32) {
            rssiText = "ESP32 Device - " + rssiText;
        }
        
        holder.deviceRssi.setText(rssiText);
        
        // Highlight selected device and ESP32 devices
        if (position == selectedPosition) {
            holder.itemView.setBackgroundColor(0xFFE3F2FD); // Light blue background
        } else if (isEsp32) {
            holder.itemView.setBackgroundColor(0xFFE8F5E8); // Light green background for ESP32
        } else {
            holder.itemView.setBackgroundColor(0xFFFFFFFF); // White background
        }
    }
    
    @Override
    public int getItemCount() {
        return devices.size();
    }
    
    class DeviceViewHolder extends RecyclerView.ViewHolder {
        TextView deviceName;
        TextView deviceRssi;
        
        DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            deviceName = itemView.findViewById(android.R.id.text1);
            deviceRssi = itemView.findViewById(android.R.id.text2);
            
            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && deviceClickListener != null) {
                    // Update selected position
                    int previousSelected = selectedPosition;
                    selectedPosition = position;
                    
                    // Notify changes
                    if (previousSelected != -1) {
                        notifyItemChanged(previousSelected);
                    }
                    notifyItemChanged(selectedPosition);
                    
                    deviceClickListener.onDeviceClick(devices.get(position), position);
                }
            });
        }
    }
}
