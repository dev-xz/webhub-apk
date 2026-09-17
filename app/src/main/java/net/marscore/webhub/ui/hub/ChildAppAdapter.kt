package net.marscore.webhub.ui.hub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.recyclerview.widget.RecyclerView
import net.marscore.webhub.R
import net.marscore.webhub.data.ChildApp
import net.marscore.webhub.icons.PresetIcons
import net.marscore.webhub.shortcuts.ShortcutHelper

/**
 * List adapter for child apps. Each item shows the resolved icon, name, domain, and active settings.
 *
 * Icon resolution mirrors [ShortcutHelper.resolveIconBitmap] but without the adaptive-canvas
 * composition (we want the raw square for display): preset → file → fallback preset.
 *
 * Clicks and long-presses are forwarded to a listener for launch / options.
 */
class ChildAppAdapter(
    private val onClick: (ChildApp) -> Unit,
    private val onLongClick: (ChildApp) -> Unit
) : RecyclerView.Adapter<ChildAppAdapter.VH>() {

    private val items = mutableListOf<ChildApp>()

    /** Cache of decoded bitmaps keyed by id, so re-bind is cheap on scroll. */
    private val iconCache = mutableMapOf<Long, Bitmap?>()

    fun submit(list: List<ChildApp>) {
        // The icon cache keys by child id, but an edit (e.g. re-favicon, new upload, preset switch)
        // overwrites the on-disk file at the SAME id without changing the ChildApp entity's fields
        // (iconPath stays filesDir/icons/<id>.png). Without invalidating the cache here, re-bind
        // would keep showing the stale decoded bitmap until a restart. Hub lists are tiny (tens of
        // items), so clearing the whole cache on every submit and re-decoding on re-bind is cheap
        // and guarantees the list reflects the current icon files. (Per design: a full refresh is
        // acceptable at this scale; per-item blink is not a concern.)
        iconCache.clear()
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /**
     * Test hook: expose the cached bitmap for [childId] so the cache-invalidation behavior can be
     * verified without a live RecyclerView binding pass.
     */
    internal fun cachedIconFor(childId: Long): Bitmap? = iconCache[childId]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_child_app, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val child = items[position]
        holder.name.text = child.name
        holder.domain.text = DomainDisplay.of(child.url)
        holder.bindIcon(child)
        holder.bindIndicators(child)
        holder.itemView.setOnClickListener { onClick(child) }
        holder.itemView.setOnLongClickListener { onLongClick(child); true }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.item_icon)
        val name: TextView = view.findViewById(R.id.item_name)
        val domain: TextView = view.findViewById(R.id.item_domain)
        private val uaIndicator: ImageView = view.findViewById(R.id.ic_ind_ua)
        private val zoomIndicator: ImageView = view.findViewById(R.id.ic_ind_zoom)
        private val sslIndicator: ImageView = view.findViewById(R.id.ic_ind_ssl)

        fun bindIcon(child: ChildApp) {
            val ctx = itemView.context
            val bmp = iconCache.getOrPut(child.id) { resolveDisplayBitmap(ctx, child) }
            if (bmp != null) {
                val rounded = RoundedBitmapDrawableFactory.create(ctx.resources, bmp).apply {
                    cornerRadius = 14f * ctx.resources.displayMetrics.density
                }
                icon.setImageDrawable(rounded)
            } else {
                icon.setImageResource(PresetIcons.pickForId(child.id).resId)
            }
        }

        fun bindIndicators(child: ChildApp) {
            val ua = when (child.uaMode) {
                "mobile" -> R.drawable.ic_ind_mobile to R.string.list_ind_ua_mobile
                "tablet" -> R.drawable.ic_ind_tablet to R.string.list_ind_ua_tablet
                "desktop" -> R.drawable.ic_ind_desktop to R.string.list_ind_ua_desktop
                else -> null
            }
            if (ua == null) {
                uaIndicator.visibility = View.GONE
                uaIndicator.contentDescription = null
                uaIndicator.setImageDrawable(null)
            } else {
                uaIndicator.setImageResource(ua.first)
                uaIndicator.contentDescription = itemView.context.getString(ua.second)
                uaIndicator.visibility = View.VISIBLE
            }

            zoomIndicator.visibility = if (child.zoomPercent > 0) View.VISIBLE else View.GONE
            sslIndicator.visibility = if (child.ignoreSsl) View.VISIBLE else View.GONE
        }
    }

    /** Resolve a display bitmap without the adaptive canvas (square crop is fine for a tile). */
    private fun resolveDisplayBitmap(context: android.content.Context, child: ChildApp): Bitmap? {
        return when (child.iconSource) {
            "preset" -> {
                val key = child.iconPath
                if (!key.isNullOrBlank()) {
                    val resId = PresetIcons.resForKey(key)
                    if (resId != 0) ShortcutHelper.renderDrawableToBitmap(context, resId) else null
                } else null
            }
            else -> {
                val path = child.iconPath
                if (!path.isNullOrBlank()) {
                    try {
                        BitmapFactory.decodeFile(path)
                    } catch (e: Exception) {
                        null
                    }
                } else null
            }
        } ?: ShortcutHelper.renderDrawableToBitmap(context, PresetIcons.pickForId(child.id).resId)
    }
}
