package remix.myplayer.appwidgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.support.annotation.DrawableRes
import android.widget.RemoteViews
import com.tencent.bugly.crashreport.CrashReport
import remix.myplayer.App
import remix.myplayer.R
import remix.myplayer.appwidgets.AppWidgetSkin.WHITE_1F
import remix.myplayer.bean.mp3.Song
import remix.myplayer.misc.exception.MusicServiceException
import remix.myplayer.request.RemoteUriRequest
import remix.myplayer.request.RequestConfig
import remix.myplayer.service.Command
import remix.myplayer.service.MusicService
import remix.myplayer.service.MusicService.EXTRA_CONTROL
import remix.myplayer.ui.activity.MainActivity
import remix.myplayer.util.DensityUtil
import remix.myplayer.util.ImageUriUtil.getSearchRequestWithAlbumType
import remix.myplayer.util.LogUtil
import remix.myplayer.util.PlayListUtil

/**
 * @ClassName
 * @Description
 * @Author Xiaoborui
 * @Date 2016/12/28 15:50
 */

abstract class BaseAppwidget : AppWidgetProvider() {

    protected lateinit var skin: AppWidgetSkin
    protected var bitmap: Bitmap? = null

    private val defaultDrawableRes: Int
        @DrawableRes
        get() = if (skin == WHITE_1F) R.drawable.album_empty_bg_night else R.drawable.album_empty_bg_day

    private fun buildServicePendingIntent(context: Context, componentName: ComponentName, cmd: Int): PendingIntent {
        val intent = Intent(MusicService.ACTION_APPWIDGET_OPERATE)
        intent.putExtra(EXTRA_CONTROL, cmd)
        intent.component = componentName
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isAllowForForegroundService(cmd)) {
            PendingIntent.getForegroundService(context, cmd, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            PendingIntent.getService(context, cmd, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }

    private fun isAllowForForegroundService(cmd: Int): Boolean {
        return cmd != Command.CHANGE_MODEL && cmd != Command.LOVE && cmd != Command.TOGGLE_TIMER
    }

    protected fun hasInstances(context: Context): Boolean {
        try {
            val appIds = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, javaClass))
            return appIds != null && appIds.isNotEmpty()
        } catch (e: Exception) {
            CrashReport.postCatchedException(MusicServiceException("hasInstance", e))
        }
        return false
    }

    protected fun updateCover(service: MusicService, remoteViews: RemoteViews, appWidgetIds: IntArray?, reloadCover: Boolean) {
        val song = service.currentSong ?: return
        //设置封面
        if (!reloadCover) {
            if (bitmap != null && !bitmap!!.isRecycled) {
                LogUtil.d(TAG, "复用Bitmap: $bitmap")
                remoteViews.setImageViewBitmap(R.id.appwidget_image, bitmap)
            } else {
                LogUtil.d(TAG, "Bitmap复用失败: $bitmap")
                remoteViews.setImageViewResource(R.id.appwidget_image, defaultDrawableRes)
            }
            pushUpdate(service, appWidgetIds, remoteViews)
        } else {
            val size = if (this.javaClass.simpleName.contains("Big")) IMAGE_SIZE_BIG else IMAGE_SIZE_MEDIUM
            object : RemoteUriRequest(getSearchRequestWithAlbumType(song), RequestConfig.Builder(size, size).build()) {
                override fun onError(errMsg: String) {
                    LogUtil.d(TAG, "onError: $errMsg --- 清空bitmap: $bitmap")
                    bitmap = null
                    remoteViews.setImageViewResource(R.id.appwidget_image, defaultDrawableRes)
                    pushUpdate(service, appWidgetIds, remoteViews)
                }

                override fun onSuccess(result: Bitmap?) {
                    try {
                        if (result != bitmap && bitmap != null) {
                            LogUtil.d(TAG, "onSuccess --- 回收Bitmap: $bitmap")
                            bitmap = null
                        }
//                        bitmap = MusicService.copy(result);
                        bitmap = result
                        LogUtil.d(TAG, "onSuccess --- 获取Bitmap: $bitmap")
                        if (bitmap != null) {
                            remoteViews.setImageViewBitmap(R.id.appwidget_image, bitmap)
                        } else {
                            remoteViews.setImageViewResource(R.id.appwidget_image, defaultDrawableRes)
                        }

                    } catch (e: Exception) {
                        LogUtil.d(TAG, "onSuccess --- 发生异常: $e")
                    } finally {
                        pushUpdate(service, appWidgetIds, remoteViews)
                    }
                }
            }.load()
        }
    }

    protected fun buildAction(context: Context, views: RemoteViews) {
        val componentNameForService = ComponentName(context, MusicService::class.java)
        views.setOnClickPendingIntent(R.id.appwidget_toggle, buildServicePendingIntent(context, componentNameForService, Command.TOGGLE))
        views.setOnClickPendingIntent(R.id.appwidget_prev, buildServicePendingIntent(context, componentNameForService, Command.PREV))
        views.setOnClickPendingIntent(R.id.appwidget_next, buildServicePendingIntent(context, componentNameForService, Command.NEXT))
        views.setOnClickPendingIntent(R.id.appwidget_model, buildServicePendingIntent(context, componentNameForService, Command.CHANGE_MODEL))
        views.setOnClickPendingIntent(R.id.appwidget_love, buildServicePendingIntent(context, componentNameForService, Command.LOVE))
        views.setOnClickPendingIntent(R.id.appwidget_timer, buildServicePendingIntent(context, componentNameForService, Command.TOGGLE_TIMER))

        val action = Intent(context, MainActivity::class.java)
        action.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        views.setOnClickPendingIntent(R.id.appwidget_clickable, PendingIntent.getActivity(context, 0, action, 0))
    }

    protected fun pushUpdate(context: Context, appWidgetId: IntArray?, remoteViews: RemoteViews) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        if (appWidgetId != null) {
            appWidgetManager.updateAppWidget(appWidgetId, remoteViews)
            return
        }
        appWidgetManager.updateAppWidget(ComponentName(context, javaClass), remoteViews)
    }

    protected fun updateRemoteViews(service: MusicService, remoteViews: RemoteViews, song: Song) {
        //        int skin = SPUtil.getValue(App.getContext(),SPUtil.SETTING_KEY.NAME,SPUtil.SETTING_KEY.APP_WIDGET_SKIN,SKIN_WHITE_1F);
        //        skin = skin == SKIN_TRANSPARENT ? AppWidgetSkin.TRANSPARENT : AppWidgetSkin.WHITE_1F;
        //        updateBackground(remoteViews);
        updateTitle(remoteViews, song)
        updateArtist(remoteViews, song)
        //        updateSkin(remoteViews);
        updatePlayPause(service, remoteViews)
        updateLove(remoteViews, song)
        updateModel(remoteViews)
        updateNextAndPrev(remoteViews)
        updateProgress(service, remoteViews, song)
        updateTimer(remoteViews)
    }

    private fun updateTimer(remoteViews: RemoteViews) {
        remoteViews.setImageViewResource(R.id.appwidget_timer, skin.timerRes)
    }

    //    protected void updateSkin(RemoteViews remoteViews){
    //        Drawable skinDrawable = Theme.TintDrawable(R.drawable.widget_btn_skin, skin.getBtnColor());
    //        remoteViews.setImageViewBitmap(R.id.appwidget_skin,drawableToBitmap(skinDrawable));
    //    }

    private fun updateProgress(service: MusicService, remoteViews: RemoteViews, song: Song) {
        //设置时间
        remoteViews.setTextColor(R.id.appwidget_progress, skin.progressColor)
        //进度
        remoteViews.setProgressBar(R.id.appwidget_seekbar, song.duration.toInt(), service.progress, false)
    }

    private fun updateLove(remoteViews: RemoteViews, song: Song) {
        //是否收藏
        if (PlayListUtil.isLove(song.id) != PlayListUtil.EXIST) {
            remoteViews.setImageViewResource(R.id.appwidget_love, skin.loveRes)
        } else {
            remoteViews.setImageViewResource(R.id.appwidget_love, skin.lovedRes)
        }
    }

    private fun updateNextAndPrev(remoteViews: RemoteViews) {
        //上下首歌曲
        remoteViews.setImageViewResource(R.id.appwidget_next, skin.nextRes)
        remoteViews.setImageViewResource(R.id.appwidget_prev, skin.prevRes)
    }

    private fun updateModel(remoteViews: RemoteViews) {
        //播放模式
        remoteViews.setImageViewResource(R.id.appwidget_model, skin.modeRes)
    }

    private fun updatePlayPause(service: MusicService, remoteViews: RemoteViews) {
        //播放暂停按钮
        remoteViews.setImageViewResource(R.id.appwidget_toggle, if (service.isPlaying) skin.pauseRes else skin.playRes)
    }

    private fun updateTitle(remoteViews: RemoteViews, song: Song) {
        //歌曲名
        remoteViews.setTextColor(R.id.appwidget_title, skin.titleColor)
        remoteViews.setTextViewText(R.id.appwidget_title, song.title)
    }

    private fun updateArtist(remoteViews: RemoteViews, song: Song) {
        //歌手名
        remoteViews.setTextColor(R.id.appwidget_artist, skin.artistColor)
        remoteViews.setTextViewText(R.id.appwidget_artist, song.artist)
    }

    protected fun updateBackground(remoteViews: RemoteViews) {
        remoteViews.setImageViewResource(R.id.appwidget_clickable, skin.background)
    }

    abstract fun updateWidget(service: MusicService, appWidgetIds: IntArray?, reloadCover: Boolean)

    companion object {
        val SKIN_WHITE_1F = 1//白色不带透明
        val SKIN_TRANSPARENT = 2//透明
        private val TAG = "桌面部件"
        private val IMAGE_SIZE_BIG = DensityUtil.dip2px(App.getContext(), 270f)
        private val IMAGE_SIZE_MEDIUM = DensityUtil.dip2px(App.getContext(), 72f)

        fun drawableToBitmap(drawable: Drawable): Bitmap {
            // 取 drawable 的长宽
            val w = drawable.intrinsicWidth
            val h = drawable.intrinsicHeight

            // 取 drawable 的颜色格式
            val config = if (drawable.opacity != PixelFormat.OPAQUE)
                Bitmap.Config.ARGB_8888
            else
                Bitmap.Config.RGB_565
            // 建立对应 bitmap
            val bitmap = Bitmap.createBitmap(w, h, config)
            // 建立对应 bitmap 的画布
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, w, h)
            // 把 drawable 内容画到画布中
            drawable.draw(canvas)
            return bitmap
        }
    }
}
