package com.example.concure.data;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import android.content.Context;

/**
 * Room database for storing sensor readings and curing sessions
 * Provides access to DAOs for data operations
 */
@Database(
    entities = {SensorReading.class, CuringSession.class},
    version = 1,
    exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {
    
    private static volatile AppDatabase INSTANCE;
    
    public abstract SensorReadingDao sensorReadingDao();
    public abstract CuringSessionDao curingSessionDao();
    
    /**
     * Get singleton instance of the database
     */
    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                        context.getApplicationContext(),
                        AppDatabase.class,
                        "concure_database"
                    ).build();
                }
            }
        }
        return INSTANCE;
    }
}
