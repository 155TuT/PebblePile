package com.pebblepile.android.db

import android.content.Context

/**
 * Singleton Room database provider for the application. One database instance is shared so that
 * revision counters cannot diverge across multiple connections.
 */
object RoomStore {
    @Volatile
    private var database: PebbleDatabase? = null

    fun repository(context: Context): RoomCoreRepository {
        val db = database ?: synchronized(this) {
            database ?: PebbleDatabase.build(context.applicationContext, "pebblepile.sqlite").also {
                database = it
            }
        }
        return RoomCoreRepository(db)
    }
}
