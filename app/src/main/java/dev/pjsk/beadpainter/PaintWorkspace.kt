package dev.pjsk.beadpainter

object PaintWorkspace {
    var source: PixelImage? = null
        private set
    var options: ConvertOptions = ConvertOptions()
        private set
    var target: IntArray? = null
        private set
    var adjusted: PixelImage? = null
        private set
    var revision: Int = 0
        private set

    fun setSource(image: PixelImage) {
        source = image
        target = null
        adjusted = null
        revision++
    }

    fun invalidate() {
        target = null
    }

    fun update(next: ConvertOptions) {
        options = next
        target = null
        adjusted = null
        val image = source ?: return
        val mapped = ImageConverter.convert(image, next)
        adjusted = ImageConverter.adjustedPreview(image, next)
        target = mapped
        revision++
    }
}
