package app.shelfie.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import app.shelfie.R
import coil.compose.AsyncImage

/**
 * Cover artwork with a default placeholder for items whose cover is missing
 * or fails to load. [completed] is accepted for call-site compatibility but
 * no longer changes the artwork (the podcast-era blur/checkmark is gone).
 */
@Composable
fun CoverImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    @Suppress("UNUSED_PARAMETER") completed: Boolean = false,
) {
    val default = painterResource(R.drawable.ic_default_cover)
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        placeholder = default,
        error = default,
        fallback = default,
        contentScale = contentScale,
        modifier = modifier,
    )
}
