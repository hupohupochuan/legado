package io.legado.app.ui.association

import io.legado.app.base.adapter.ItemViewHolder

internal fun ItemViewHolder.safeLayoutPosition(itemCount: Int): Int? {
    return layoutPosition.takeIf { it in 0 until itemCount }
}

internal fun ItemViewHolder.safeBindingAdapterPosition(itemCount: Int): Int? {
    return bindingAdapterPosition.takeIf { it in 0 until itemCount }
}
