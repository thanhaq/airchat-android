package dev.offlinemesh.airchat.crypto

data class QrCodeMatrix(
    val size: Int,
    private val modules: BooleanArray
) {
    fun isDark(x: Int, y: Int): Boolean {
        require(x in 0 until size && y in 0 until size) { "QR coordinate out of bounds: $x,$y" }
        return modules[y * size + x]
    }
}

object QrCodeEncoder {
    private const val VERSION = 5
    private const val SIZE = VERSION * 4 + 17
    private const val DATA_CODEWORDS = 108
    private const val ECC_CODEWORDS = 26
    private const val MASK = 0
    private const val FORMAT_ECL_LOW = 1
    private const val MAX_BYTE_PAYLOAD = 106

    fun encodeText(text: String): QrCodeMatrix {
        val data = text.toByteArray(Charsets.UTF_8)
        require(data.size <= MAX_BYTE_PAYLOAD) {
            "QR payload is ${data.size} bytes; max is $MAX_BYTE_PAYLOAD bytes"
        }

        val dataCodewords = encodeData(data)
        val ecc = reedSolomonRemainder(dataCodewords, reedSolomonDivisor(ECC_CODEWORDS))
        val allCodewords = dataCodewords + ecc
        val modules = BooleanArray(SIZE * SIZE)
        val reserved = BooleanArray(SIZE * SIZE)

        drawFunctionPatterns(modules, reserved)
        drawCodewords(modules, reserved, allCodewords)
        return QrCodeMatrix(SIZE, modules)
    }

    private fun encodeData(data: ByteArray): IntArray {
        val buffer = BitBuffer()
        buffer.append(0b0100, 4)
        buffer.append(data.size, 8)
        data.forEach { buffer.append(it.toInt() and 0xFF, 8) }

        val capacityBits = DATA_CODEWORDS * 8
        buffer.append(0, minOf(4, capacityBits - buffer.size))
        while (buffer.size % 8 != 0) {
            buffer.append(0, 1)
        }

        val codewords = buffer.toCodewords().toMutableList()
        var pad = true
        while (codewords.size < DATA_CODEWORDS) {
            codewords += if (pad) 0xEC else 0x11
            pad = !pad
        }
        return codewords.toIntArray()
    }

    private fun drawFunctionPatterns(modules: BooleanArray, reserved: BooleanArray) {
        drawFinder(modules, reserved, 3, 3)
        drawFinder(modules, reserved, SIZE - 4, 3)
        drawFinder(modules, reserved, 3, SIZE - 4)
        drawAlignment(modules, reserved, 30, 30)

        for (i in 0 until SIZE) {
            if (!reserved[index(6, i)]) setFunctionModule(modules, reserved, 6, i, i % 2 == 0)
            if (!reserved[index(i, 6)]) setFunctionModule(modules, reserved, i, 6, i % 2 == 0)
        }

        setFunctionModule(modules, reserved, 8, SIZE - 8, true)
        drawFormatBits(modules, reserved)
    }

    private fun drawFinder(modules: BooleanArray, reserved: BooleanArray, centerX: Int, centerY: Int) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val x = centerX + dx
                val y = centerY + dy
                if (x !in 0 until SIZE || y !in 0 until SIZE) continue
                val distance = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                setFunctionModule(modules, reserved, x, y, distance != 2 && distance != 4)
            }
        }
    }

    private fun drawAlignment(modules: BooleanArray, reserved: BooleanArray, centerX: Int, centerY: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                val distance = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                setFunctionModule(modules, reserved, centerX + dx, centerY + dy, distance != 1)
            }
        }
    }

    private fun drawFormatBits(modules: BooleanArray, reserved: BooleanArray) {
        val bits = formatBits()
        for (i in 0..5) setFunctionModule(modules, reserved, 8, i, getBit(bits, i))
        setFunctionModule(modules, reserved, 8, 7, getBit(bits, 6))
        setFunctionModule(modules, reserved, 8, 8, getBit(bits, 7))
        setFunctionModule(modules, reserved, 7, 8, getBit(bits, 8))
        for (i in 9..14) setFunctionModule(modules, reserved, 14 - i, 8, getBit(bits, i))

        for (i in 0..7) setFunctionModule(modules, reserved, SIZE - 1 - i, 8, getBit(bits, i))
        for (i in 8..14) setFunctionModule(modules, reserved, 8, SIZE - 15 + i, getBit(bits, i))
        setFunctionModule(modules, reserved, 8, SIZE - 8, true)
    }

    private fun drawCodewords(modules: BooleanArray, reserved: BooleanArray, codewords: IntArray) {
        val bits = BooleanArray(codewords.size * 8)
        codewords.forEachIndexed { index, codeword ->
            for (i in 0 until 8) {
                bits[index * 8 + i] = getBit(codeword, 7 - i)
            }
        }

        var bitIndex = 0
        var upward = true
        var x = SIZE - 1
        while (x > 0) {
            if (x == 6) x--
            for (i in 0 until SIZE) {
                val y = if (upward) SIZE - 1 - i else i
                for (dx in 0..1) {
                    val xx = x - dx
                    if (reserved[index(xx, y)]) continue
                    var dark = bitIndex < bits.size && bits[bitIndex]
                    bitIndex++
                    if (mask(xx, y)) dark = !dark
                    modules[index(xx, y)] = dark
                }
            }
            upward = !upward
            x -= 2
        }
    }

    private fun formatBits(): Int {
        val data = (FORMAT_ECL_LOW shl 3) or MASK
        var remainder = data
        repeat(10) {
            remainder = (remainder shl 1) xor ((remainder ushr 9) * 0x537)
        }
        return ((data shl 10) or remainder) xor 0x5412
    }

    private fun mask(x: Int, y: Int): Boolean = (x + y) % 2 == 0

    private fun setFunctionModule(
        modules: BooleanArray,
        reserved: BooleanArray,
        x: Int,
        y: Int,
        dark: Boolean
    ) {
        val index = index(x, y)
        modules[index] = dark
        reserved[index] = true
    }

    private fun reedSolomonDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in result.indices) {
                result[j] = reedSolomonMultiply(result[j], root)
                if (j + 1 < result.size) {
                    result[j] = result[j] xor result[j + 1]
                }
            }
            root = reedSolomonMultiply(root, 0x02)
        }
        return result
    }

    private fun reedSolomonRemainder(data: IntArray, divisor: IntArray): IntArray {
        val result = IntArray(divisor.size)
        data.forEach { codeword ->
            val factor = codeword xor result[0]
            for (i in 0 until result.lastIndex) {
                result[i] = result[i + 1]
            }
            result[result.lastIndex] = 0
            for (i in result.indices) {
                result[i] = result[i] xor reedSolomonMultiply(divisor[i], factor)
            }
        }
        return result
    }

    private fun reedSolomonMultiply(left: Int, right: Int): Int {
        var x = left
        var y = right
        var result = 0
        while (y != 0) {
            if ((y and 1) != 0) result = result xor x
            x = x shl 1
            if ((x and 0x100) != 0) x = x xor 0x11D
            y = y ushr 1
        }
        return result and 0xFF
    }

    private fun getBit(value: Int, index: Int): Boolean = ((value ushr index) and 1) != 0

    private fun index(x: Int, y: Int): Int = y * SIZE + x

    private class BitBuffer {
        private val bits = mutableListOf<Boolean>()
        val size: Int
            get() = bits.size

        fun append(value: Int, length: Int) {
            require(length >= 0)
            for (i in length - 1 downTo 0) {
                bits += ((value ushr i) and 1) != 0
            }
        }

        fun toCodewords(): IntArray {
            require(bits.size % 8 == 0)
            return IntArray(bits.size / 8) { index ->
                var value = 0
                for (i in 0 until 8) {
                    value = (value shl 1) or if (bits[index * 8 + i]) 1 else 0
                }
                value
            }
        }
    }
}
