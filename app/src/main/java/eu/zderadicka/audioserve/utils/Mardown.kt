package eu.zderadicka.audioserve.utils

import android.content.Context
import android.text.Spannable
import ru.noties.markwon.Markwon
import ru.noties.markwon.SpannableConfiguration
import ru.noties.markwon.renderer.SpannableRenderer



fun fromMarkdown(ctx: Context, md:String): CharSequence {
    val parser = Markwon.createParser();
    val configuration = SpannableConfiguration.create(ctx);
    val renderer = SpannableRenderer()
    val node = parser.parse(md)
    val text = renderer.render(configuration, node)
    return text
}