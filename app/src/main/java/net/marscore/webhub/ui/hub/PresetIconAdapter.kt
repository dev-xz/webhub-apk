package net.marscore.webhub.ui.hub

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import net.marscore.webhub.R
import net.marscore.webhub.icons.PresetIcon
import net.marscore.webhub.icons.PresetIcons

/**
 * Horizontal-row adapter for the preset icon picker inside the add/edit dialog (task 4.8).
 *
 * Tapping a tile selects it; the selected tile gets a highlighted background. A single
 * selection is tracked; the caller can read [selectedKey] on save.
 */
class PresetIconAdapter(
    private val presets: List<PresetIcon> = PresetIcons.all(),
    private val onSelected: (PresetIcon) -> Unit
) : RecyclerView.Adapter<PresetIconAdapter.VH>() {

    var selectedKey: String? = null
        private set

    /** Pre-select a key (used when editing an existing preset-icon child). */
    fun setSelectedKey(key: String?) {
        selectedKey = key
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_preset_icon, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = presets.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val preset = presets[position]
        holder.image.setImageResource(preset.resId)
        val isSel = preset.key == selectedKey
        holder.itemView.isSelected = isSel
        holder.itemView.background = GradientDrawable().apply {
            cornerRadius = 12f * holder.itemView.resources.displayMetrics.density
            if (isSel) {
                setColor(Color.TRANSPARENT)
                setStroke(
                    (2f * holder.itemView.resources.displayMetrics.density).toInt(),
                    ContextCompat.getColor(holder.itemView.context, R.color.hub_teal)
                )
            } else {
                setColor(Color.TRANSPARENT)
            }
        }
        holder.image.alpha = 1f
        holder.itemView.setOnClickListener {
            selectedKey = preset.key
            notifyDataSetChanged()
            onSelected(preset)
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.preset_icon_image)
    }
}
