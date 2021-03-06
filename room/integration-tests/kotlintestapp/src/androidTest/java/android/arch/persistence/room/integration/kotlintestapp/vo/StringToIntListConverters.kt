package android.arch.persistence.room.integration.kotlintestapp.vo

import android.arch.persistence.room.TypeConverter
import android.arch.persistence.room.util.StringUtil

object StringToIntListConverters {
    @TypeConverter
    // Specifying that a static method should be generated. Otherwise, the compiler looks for the
    // constructor of the class, and a object has a private constructor.
    @JvmStatic
    fun stringToIntList(data: String?): List<Int>? =
            if (data == null) null else StringUtil.splitToIntList(data)

    @TypeConverter
    @JvmStatic
    fun intListToString(ints: List<Int>?): String? =
            if (ints == null) null else StringUtil.joinIntoString(ints)
}
