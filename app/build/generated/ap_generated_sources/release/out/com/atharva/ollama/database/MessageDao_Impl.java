package com.atharva.ollama.database;

import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class MessageDao_Impl implements MessageDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<Message> __insertionAdapterOfMessage;

  private final EntityDeletionOrUpdateAdapter<Message> __deletionAdapterOfMessage;

  private final SharedSQLiteStatement __preparedStmtOfDeleteAllMessagesInConversation;

  public MessageDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfMessage = new EntityInsertionAdapter<Message>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `messages` (`id`,`conversationId`,`role`,`content`,`timestamp`,`imagePaths`,`sourcesJson`) VALUES (nullif(?, 0),?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement, final Message entity) {
        statement.bindLong(1, entity.id);
        statement.bindLong(2, entity.conversationId);
        if (entity.role == null) {
          statement.bindNull(3);
        } else {
          statement.bindString(3, entity.role);
        }
        if (entity.content == null) {
          statement.bindNull(4);
        } else {
          statement.bindString(4, entity.content);
        }
        statement.bindLong(5, entity.timestamp);
        if (entity.imagePaths == null) {
          statement.bindNull(6);
        } else {
          statement.bindString(6, entity.imagePaths);
        }
        if (entity.sourcesJson == null) {
          statement.bindNull(7);
        } else {
          statement.bindString(7, entity.sourcesJson);
        }
      }
    };
    this.__deletionAdapterOfMessage = new EntityDeletionOrUpdateAdapter<Message>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "DELETE FROM `messages` WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement, final Message entity) {
        statement.bindLong(1, entity.id);
      }
    };
    this.__preparedStmtOfDeleteAllMessagesInConversation = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM messages WHERE conversationId = ?";
        return _query;
      }
    };
  }

  @Override
  public long insert(final Message message) {
    __db.assertNotSuspendingTransaction();
    __db.beginTransaction();
    try {
      final long _result = __insertionAdapterOfMessage.insertAndReturnId(message);
      __db.setTransactionSuccessful();
      return _result;
    } finally {
      __db.endTransaction();
    }
  }

  @Override
  public void delete(final Message message) {
    __db.assertNotSuspendingTransaction();
    __db.beginTransaction();
    try {
      __deletionAdapterOfMessage.handle(message);
      __db.setTransactionSuccessful();
    } finally {
      __db.endTransaction();
    }
  }

  @Override
  public void deleteAllMessagesInConversation(final long conversationId) {
    __db.assertNotSuspendingTransaction();
    final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteAllMessagesInConversation.acquire();
    int _argIndex = 1;
    _stmt.bindLong(_argIndex, conversationId);
    try {
      __db.beginTransaction();
      try {
        _stmt.executeUpdateDelete();
        __db.setTransactionSuccessful();
      } finally {
        __db.endTransaction();
      }
    } finally {
      __preparedStmtOfDeleteAllMessagesInConversation.release(_stmt);
    }
  }

  @Override
  public List<Message> getMessagesForConversation(final long conversationId) {
    final String _sql = "SELECT * FROM messages WHERE conversationId = ? ORDER BY timestamp ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, conversationId);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
      final int _cursorIndexOfConversationId = CursorUtil.getColumnIndexOrThrow(_cursor, "conversationId");
      final int _cursorIndexOfRole = CursorUtil.getColumnIndexOrThrow(_cursor, "role");
      final int _cursorIndexOfContent = CursorUtil.getColumnIndexOrThrow(_cursor, "content");
      final int _cursorIndexOfTimestamp = CursorUtil.getColumnIndexOrThrow(_cursor, "timestamp");
      final int _cursorIndexOfImagePaths = CursorUtil.getColumnIndexOrThrow(_cursor, "imagePaths");
      final int _cursorIndexOfSourcesJson = CursorUtil.getColumnIndexOrThrow(_cursor, "sourcesJson");
      final List<Message> _result = new ArrayList<Message>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final Message _item;
        _item = new Message();
        _item.id = _cursor.getLong(_cursorIndexOfId);
        _item.conversationId = _cursor.getLong(_cursorIndexOfConversationId);
        if (_cursor.isNull(_cursorIndexOfRole)) {
          _item.role = null;
        } else {
          _item.role = _cursor.getString(_cursorIndexOfRole);
        }
        if (_cursor.isNull(_cursorIndexOfContent)) {
          _item.content = null;
        } else {
          _item.content = _cursor.getString(_cursorIndexOfContent);
        }
        _item.timestamp = _cursor.getLong(_cursorIndexOfTimestamp);
        if (_cursor.isNull(_cursorIndexOfImagePaths)) {
          _item.imagePaths = null;
        } else {
          _item.imagePaths = _cursor.getString(_cursorIndexOfImagePaths);
        }
        if (_cursor.isNull(_cursorIndexOfSourcesJson)) {
          _item.sourcesJson = null;
        } else {
          _item.sourcesJson = _cursor.getString(_cursorIndexOfSourcesJson);
        }
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public List<Message> getRecentMessages(final long conversationId, final int limit) {
    final String _sql = "SELECT * FROM messages WHERE conversationId = ? ORDER BY timestamp DESC LIMIT ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, conversationId);
    _argIndex = 2;
    _statement.bindLong(_argIndex, limit);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
      final int _cursorIndexOfConversationId = CursorUtil.getColumnIndexOrThrow(_cursor, "conversationId");
      final int _cursorIndexOfRole = CursorUtil.getColumnIndexOrThrow(_cursor, "role");
      final int _cursorIndexOfContent = CursorUtil.getColumnIndexOrThrow(_cursor, "content");
      final int _cursorIndexOfTimestamp = CursorUtil.getColumnIndexOrThrow(_cursor, "timestamp");
      final int _cursorIndexOfImagePaths = CursorUtil.getColumnIndexOrThrow(_cursor, "imagePaths");
      final int _cursorIndexOfSourcesJson = CursorUtil.getColumnIndexOrThrow(_cursor, "sourcesJson");
      final List<Message> _result = new ArrayList<Message>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final Message _item;
        _item = new Message();
        _item.id = _cursor.getLong(_cursorIndexOfId);
        _item.conversationId = _cursor.getLong(_cursorIndexOfConversationId);
        if (_cursor.isNull(_cursorIndexOfRole)) {
          _item.role = null;
        } else {
          _item.role = _cursor.getString(_cursorIndexOfRole);
        }
        if (_cursor.isNull(_cursorIndexOfContent)) {
          _item.content = null;
        } else {
          _item.content = _cursor.getString(_cursorIndexOfContent);
        }
        _item.timestamp = _cursor.getLong(_cursorIndexOfTimestamp);
        if (_cursor.isNull(_cursorIndexOfImagePaths)) {
          _item.imagePaths = null;
        } else {
          _item.imagePaths = _cursor.getString(_cursorIndexOfImagePaths);
        }
        if (_cursor.isNull(_cursorIndexOfSourcesJson)) {
          _item.sourcesJson = null;
        } else {
          _item.sourcesJson = _cursor.getString(_cursorIndexOfSourcesJson);
        }
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public int getMessageCount(final long conversationId) {
    final String _sql = "SELECT COUNT(*) FROM messages WHERE conversationId = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, conversationId);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _result;
      if (_cursor.moveToFirst()) {
        _result = _cursor.getInt(0);
      } else {
        _result = 0;
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public String getFirstUserMessage(final long conversationId) {
    final String _sql = "SELECT content FROM messages WHERE conversationId = ? AND role = 'user' ORDER BY timestamp ASC LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, conversationId);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final String _result;
      if (_cursor.moveToFirst()) {
        if (_cursor.isNull(0)) {
          _result = null;
        } else {
          _result = _cursor.getString(0);
        }
      } else {
        _result = null;
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
