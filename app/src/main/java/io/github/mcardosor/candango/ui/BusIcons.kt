package io.github.mcardosor.candango.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser
import org.maplibre.android.maps.Style

/**
 * Ícones dos ônibus no mapa, desenhados uma vez por cor (as cores das linhas são uma paleta fixa).
 * Mesmo desenho da versão web: "seta:#cor" tem uma ponta para o norte (o mapa gira pelo rumo),
 * "bola:#cor" é para quando o GPS não informa o rumo.
 */
object BusIcons {
    const val GLYPH = "onibus-glifo"
    private const val SIZE = 96
    private const val C = SIZE / 2f
    private const val R = 28f

    // ícone "directions_bus" do Material, em caixa de 24 unidades
    private const val BUS_PATH =
        "M4 16c0 .88.39 1.67 1 2.22V20a1 1 0 0 0 1 1h1a1 1 0 0 0 1-1v-1h8v1a1 1 0 0 0 1 1h1a1 1 0 0 0 1-1v-1.78" +
            "c.61-.55 1-1.34 1-2.22V6c0-3.5-3.58-4-8-4s-8 .5-8 4v10zm3.5 1a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z" +
            "m9 0a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm1.5-6H6V6h12v5z"

    fun addTo(style: Style, colors: List<String>) {
        for (c in colors) {
            style.addImage("seta:$c", marker(Color.parseColor(c), arrow = true))
            style.addImage("bola:$c", marker(Color.parseColor(c), arrow = false))
        }
        style.addImage(GLYPH, glyph())
    }

    private fun marker(color: Int, arrow: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val shape = Path().apply {
            addCircle(C, C, R, Path.Direction.CW)
            if (arrow) {
                // ponta para cima, saindo da bolinha
                moveTo(C - 15f, C - R + 8f)
                lineTo(C, 4f)
                lineTo(C + 15f, C - R + 8f)
                close()
            }
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f; strokeJoin = Paint.Join.ROUND
        }
        cv.drawPath(shape, stroke)
        cv.drawPath(shape, fill)
        return bmp
    }

    private fun glyph(): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val path = PathParser.createPathFromPathData(BUS_PATH)
        val s = 32f / 24f
        path.transform(Matrix().apply { setScale(s, s); postTranslate(C - 16f, C - 16f) })
        Canvas(bmp).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        return bmp
    }
}
