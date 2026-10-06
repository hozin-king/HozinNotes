package com.hozinking.notes.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface PasswordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(PasswordEntry e);

    @Update
    void update(PasswordEntry e);

    @Delete
    void delete(PasswordEntry e);

    @Query("DELETE FROM password_entries WHERE id = :id")
    void deleteById(long id);

    @Query("SELECT * FROM password_entries WHERE id = :id LIMIT 1")
    PasswordEntry getById(long id);

    @Query("SELECT * FROM password_entries ORDER BY favorite DESC, updatedAt DESC")
    LiveData<List<PasswordEntry>> observeAll();

    @Query("SELECT * FROM password_entries WHERE title LIKE '%' || :q || '%' " +
           "OR username LIKE '%' || :q || '%' OR url LIKE '%' || :q || '%' " +
           "ORDER BY favorite DESC, updatedAt DESC")
    LiveData<List<PasswordEntry>> search(String q);

    @Query("SELECT COUNT(*) FROM password_entries")
    int count();
}
