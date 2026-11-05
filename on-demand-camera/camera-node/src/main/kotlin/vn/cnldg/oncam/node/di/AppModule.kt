package vn.cnldg.oncam.node.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Đánh dấu CoroutineScope sống cùng ứng dụng. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Các phụ thuộc dùng chung toàn app. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** Scope sống cùng tiến trình app (không bị huỷ khi Activity đóng). */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
