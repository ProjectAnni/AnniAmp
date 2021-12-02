package moe.mmf.anni_amp

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.room.Room
import moe.mmf.anni_amp.repo.RepoDatabase
import moe.mmf.anni_amp.repo.RepoHelper
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val repo = RepoHelper(
            "repo",
            "https://github.com/project-anni/repo",
            applicationContext.dataDir,
        )
        if (repo.needInitialize()) {
            thread {
                val db = Room.databaseBuilder(
                    applicationContext,
                    RepoDatabase::class.java, "anni-db",
                ).build()

                repo.initialize(db)
                db.close()
            }
        }
    }
}