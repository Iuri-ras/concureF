package com.example.concure.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import androidx.room.Delete;
import java.util.List;

/**
 * Data Access Object for curing sessions
 * Provides methods for database operations on curing data
 */
@Dao
public interface CuringSessionDao {
    
    @Insert
    long insert(CuringSession session);
    
    @Update
    void update(CuringSession session);
    
    @Query("SELECT * FROM curing_sessions WHERE isActive = 1 ORDER BY startTimestamp DESC LIMIT 1")
    CuringSession getActiveSession();
    
    @Query("SELECT * FROM curing_sessions ORDER BY startTimestamp DESC")
    List<CuringSession> getAllSessions();
    
    @Query("SELECT * FROM curing_sessions WHERE startTimestamp >= :startTime AND startTimestamp <= :endTime ORDER BY startTimestamp DESC")
    List<CuringSession> getSessionsInRange(long startTime, long endTime);
    
    @Query("UPDATE curing_sessions SET isActive = 0, endTimestamp = :endTime WHERE id = :sessionId")
    void endSession(long sessionId, long endTime);
    
    @Query("UPDATE curing_sessions SET currentMaturity = :maturity WHERE id = :sessionId")
    void updateMaturity(long sessionId, float maturity);
    
    @Delete
    void delete(CuringSession session);
}
