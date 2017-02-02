/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.support.room.integration.testapp;

import com.android.support.room.Database;
import com.android.support.room.RoomDatabase;
import com.android.support.room.TypeConverter;
import com.android.support.room.TypeConverters;
import com.android.support.room.integration.testapp.dao.UserDao;
import com.android.support.room.integration.testapp.vo.User;

import java.util.Date;

@Database(entities = User.class)
@TypeConverters(TestDatabase.Converters.class)
public abstract class TestDatabase extends RoomDatabase {
    public abstract UserDao getUserDao();

    @SuppressWarnings("unused")
    public static class Converters {
        @TypeConverter
        public Date fromTimestamp(Long value) {
            return value == null ? null : new Date(value);
        }

        @TypeConverter
        public Long dateToTimestamp(Date date) {
            if (date == null) {
                return null;
            } else {
                return date.getTime();
            }
        }
    }

}
