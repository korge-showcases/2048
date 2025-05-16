import korlibs.event.*
import korlibs.image.bitmap.*
import korlibs.korge.*
import korlibs.korge.animate.*
import korlibs.korge.input.*
import korlibs.korge.service.storage.*
import korlibs.korge.tween.*
import korlibs.korge.ui.*
import korlibs.korge.view.*
import korlibs.image.color.*
import korlibs.image.font.*
import korlibs.image.format.*
import korlibs.image.text.TextAlignment
import korlibs.io.async.*
import korlibs.io.async.ObservableProperty
import korlibs.io.async.launch
import korlibs.io.file.std.*
import korlibs.korge.scene.*
import korlibs.korge.style.styles
import korlibs.korge.style.textColor
import korlibs.korge.style.textFont
import korlibs.korge.style.textSize
import korlibs.korge.view.align.*
import korlibs.math.geom.*
import korlibs.math.interpolation.*
import korlibs.time.seconds
import kotlinx.coroutines.*
import kotlin.Number
import kotlin.collections.set
import kotlin.properties.*
import kotlin.random.*

suspend fun main() = Korge(
    virtualSize = Size(480, 640),
    windowSize = Size(480, 640),
    title = "2048",
    bgcolor = RGBA(253, 247, 240),
    /**
        `gameId` is associated with the location of storage, which contains `history` and `best`.
        see [Views.realSettingsFolder]
     */
    gameId = "io.github.rezmike.game2048",
    //forceRenderEveryFrame = false, // Optimization to reduce battery usage!
) {
   sceneContainer().changeTo { IngameScene() }
}

class IngameScene : Scene() {
    var cellSize: Float = 0f
    var fieldSize: Float = 0f
    var leftIndent: Float = 0f
    var topIndent: Float = 0f
    lateinit var font: BitmapFont

    fun columnX(number: Int) = leftIndent + 10 + (cellSize + 10) * number
    fun rowY(number: Int) = topIndent + 10 + (cellSize + 10) * number

    val blocks = mutableMapOf<Int, Block>()
    var map = PositionMap(blocks)
    lateinit var history: History

    fun numberFor(blockId: Int) = blocks[blockId]!!.number
    fun deleteBlock(blockId: Int) = blocks.remove(blockId)!!.removeFromParent()

    val score = ObservableProperty(0)
    val best = ObservableProperty(0)

    var freeId = 0
    var isAnimationRunning = false
    var isGameOver = false

    lateinit var restartImg: Bitmap
    lateinit var undoImg: Bitmap

    override suspend fun SContainer.sceneInit() {
        restartImg = resourcesVfs["restart.png"].readBitmap()
        undoImg = resourcesVfs["undo.png"].readBitmap()
    }

    override suspend fun SContainer.sceneMain() {
        font = resourcesVfs["clear_sans.fnt"].readBitmapFont()

        val storage = views.storage
        history = History(storage.getOrNull("history")) {
            storage["history"] = it.toString()
        }
        best.update(storage.getOrNull("best")?.toInt() ?: 0)

        score.observe {
            if (it > best.value) best.update(it)
        }
        best.observe {
            storage["best"] = it.toString()
        }

        cellSize = views.virtualWidth / 5f
        fieldSize = 50 + 4 * cellSize
        leftIndent = (views.virtualWidth - fieldSize) / 2
        topIndent = 150f

        val bgField = roundRect(Size(fieldSize, fieldSize), RectCorners(5), fill = Colors["#b9aea0"]) {
            position(leftIndent, topIndent)
        }
        graphics {
            fill(Colors["#cec0b2"]) {
                for (i in 0..3) {
                    for (j in 0..3) {
                        roundRect(
                            10 + (10 + cellSize) * i, 10 + (10 + cellSize) * j,
                            cellSize, cellSize,
                            5f
                        )
                    }
                }
            }
        }.position(leftIndent, topIndent)

        val bgLogo = roundRect(Size(cellSize, cellSize), RectCorners(5), fill = RGBA(237, 196, 3)) {
            position(leftIndent, 30f)
        }
        text("2048", cellSize * 0.5f, Colors.WHITE, font).centerOn(bgLogo)

        fixedSizeContainer(Size(cellSize * 1.5, cellSize * 0.8)) {
            position(140, 30)
            val roundRect = roundRect(Size(cellSize * 1.5, cellSize * 0.8), RectCorners(5f), fill = Colors["#bbae9e"])
            text("SCORE", cellSize * 0.25f, RGBA(239, 226, 210), font) {
                centerXOn(this@fixedSizeContainer)
                alignTopToTopOf(this@fixedSizeContainer, 5.0)
            }
            text(score.value.toString(), cellSize * 0.5f, Colors.WHITE, font, alignment = TextAlignment.MIDDLE_CENTER) {
                position(0, 24.0)
                setTextBounds(Rectangle(0, 0, roundRect.width, roundRect.height - 24))

                score.observe {
                    text = it.toString()
                }
            }
        }

        fixedSizeContainer(Size(cellSize * 1.5, cellSize * 0.8)) {
            position(304, 30)
            val roundRect = roundRect(Size(cellSize * 1.5, cellSize * 0.8), RectCorners(5f), fill = Colors["#bbae9e"])
            text("BEST", cellSize * 0.25f, RGBA(239, 226, 210), font) {
                centerXOn(this@fixedSizeContainer)
                alignTopToTopOf(this@fixedSizeContainer, 5.0)
            }
            text(best.value.toString(), cellSize * 0.5f, Colors.WHITE, font, alignment = TextAlignment.MIDDLE_CENTER) {
                position(0, 24.0)
                setTextBounds(Rectangle(0, 0, roundRect.width, roundRect.height - 24))

                best.observe {
                    text = it.toString()
                }
            }
        }

        val btnSize = cellSize * 0.3
        uiHorizontalStack(padding = 4.0) {
            position(386, 114.0)
            container {
                val background = roundRect(Size(btnSize, btnSize), RectCorners(5f), fill = RGBA(185, 174, 160))
                image(restartImg) {
                    size(btnSize * 0.8, btnSize * 0.8)
                    centerOn(background)
                }
                onClick {
                    this@sceneMain.restart()
                }
            }
            container {
                val background = roundRect(Size(btnSize, btnSize), RectCorners(5f), fill = RGBA(185, 174, 160))
                image(undoImg) {
                    size(btnSize * 0.6, btnSize * 0.6)
                    centerOn(background)
                }
                onClick {
                    this@sceneMain.restoreField(history.undo())
                }
            }
        }

        if (!history.isEmpty()) {
            restoreField(history.currentElement)
        } else {
            generateBlockAndSave()
        }

        keys.down {
            when (it.key) {
                Key.LEFT -> moveBlocksTo(Direction.LEFT)
                Key.RIGHT -> moveBlocksTo(Direction.RIGHT)
                Key.UP -> moveBlocksTo(Direction.TOP)
                Key.DOWN -> moveBlocksTo(Direction.BOTTOM)
                else -> Unit
            }
        }

        onSwipe(20.0) {
            when (it.direction) {
                SwipeDirection.LEFT -> moveBlocksTo(Direction.LEFT)
                SwipeDirection.RIGHT -> moveBlocksTo(Direction.RIGHT)
                SwipeDirection.TOP -> moveBlocksTo(Direction.TOP)
                SwipeDirection.BOTTOM -> moveBlocksTo(Direction.BOTTOM)
            }
        }
    }

    fun SContainer.moveBlocksTo(direction: Direction) {
        if (isAnimationRunning) return
        if (!map.hasAvailableMoves()) {
            if (!isGameOver) {
                isGameOver = true
                showGameOver {
                    isGameOver = false
                    restart()
                }
            }
            return
        }

        val moves = mutableListOf<Pair<Int, Position>>()
        val merges = mutableListOf<Triple<Int, Int, Position>>()

        val newMap = calculateNewMap(map.copy(), direction, moves, merges)

        if (map != newMap) {
            isAnimationRunning = true
            showAnimation(moves, merges) {
                map = newMap
                generateBlockAndSave()
                isAnimationRunning = false
                var points = 0
                merges.forEach {
                    points += numberFor(it.first).value
                }
                score.update(score.value + points)
            }
        }
    }

    fun calculateNewMap(
        map: PositionMap,
        direction: Direction,
        moves: MutableList<Pair<Int, Position>>,
        merges: MutableList<Triple<Int, Int, Position>>
    ): PositionMap {
        val newMap = PositionMap(blocks)
        val startIndex = when (direction) {
            Direction.LEFT, Direction.TOP -> 0
            Direction.RIGHT, Direction.BOTTOM -> 3
        }
        var columnRow = startIndex

        fun newPosition(line: Int) = when (direction) {
            Direction.LEFT -> Position(columnRow++, line)
            Direction.RIGHT -> Position(columnRow--, line)
            Direction.TOP -> Position(line, columnRow++)
            Direction.BOTTOM -> Position(line, columnRow--)
        }

        for (line in 0..3) {
            var curPos = map.getNotEmptyPositionFrom(direction, line)
            columnRow = startIndex
            while (curPos != null) {
                val newPos = newPosition(line)
                val curId = map[curPos.x, curPos.y]
                map[curPos.x, curPos.y] = -1

                val nextPos = map.getNotEmptyPositionFrom(direction, line)
                val nextId = nextPos?.let { map[it.x, it.y] }
                //two blocks are equal
                if (nextId != null && numberFor(curId) == numberFor(nextId)) {
                    //merge these blocks
                    map[nextPos.x, nextPos.y] = -1
                    newMap[newPos.x, newPos.y] = curId
                    merges += Triple(curId, nextId, newPos)
                } else {
                    //add old block
                    newMap[newPos.x, newPos.y] = curId
                    moves += Pair(curId, newPos)
                }
                curPos = map.getNotEmptyPositionFrom(direction, line)
            }
        }
        return newMap
    }

    fun SContainer.showAnimation(
        moves: List<Pair<Int, Position>>,
        merges: List<Triple<Int, Int, Position>>,
        onEnd: () -> Unit
    ) = launchImmediately {
        animate {
            parallel {
                moves.forEach { (id, pos) ->
                    moveTo(blocks[id]!!, columnX(pos.x), rowY(pos.y), 0.15.seconds, Easing.LINEAR)
                }
                merges.forEach { (id1, id2, pos) ->
                    sequence {
                        parallel {
                            moveTo(blocks[id1]!!, columnX(pos.x), rowY(pos.y), 0.15.seconds, Easing.LINEAR)
                            moveTo(blocks[id2]!!, columnX(pos.x), rowY(pos.y), 0.15.seconds, Easing.LINEAR)
                        }
                        block {
                            val nextNumber = numberFor(id1).next()
                            deleteBlock(id1)
                            deleteBlock(id2)
                            createNewBlockWithId(id1, nextNumber, pos)
                        }
                        sequenceLazy {
                            animateScale(blocks[id1]!!)
                        }
                    }
                }
            }
            block {
                onEnd()
            }
        }
    }

    fun Animator.animateScale(block: Block) {
        val x = block.x
        val y = block.y
        val scale = block.scaleAvg
        tween(
            block::x[x - 4],
            block::y[y - 4],
            block::scaleAvg[scale + 0.1f],
            time = 0.1.seconds,
            easing = Easing.LINEAR
        )
        tween(
            block::x[x],
            block::y[y],
            block::scaleAvg[scale],
            time = 0.1.seconds,
            easing = Easing.LINEAR
        )
    }

    fun SContainer.showGameOver(onRestart: () -> Unit) = container {
        fun restart() {
            this@container.removeFromParent()
            onRestart()
        }

        position(leftIndent, topIndent)

        roundRect(Size(fieldSize, fieldSize), RectCorners(5), fill = Colors["#FFFFFF33"])
        text("Game Over", 60f, Colors.BLACK, font) {
            centerBetween(0f, 0f, fieldSize, fieldSize)
            y -= 60
        }
        uiText("Try again", Size(120.0, 35.0)) {
            centerBetween(0f, 0f, fieldSize, fieldSize)
            y += 20
            styles.textSize = 40.0
            styles.textFont = font
            styles.textColor = RGBA(0, 0, 0)
            onOver { styles.textColor = RGBA(90, 90, 90) }
            onOut { styles.textColor = RGBA(0, 0, 0) }
            onDown { styles.textColor = RGBA(120, 120, 120) }
            onUp { styles.textColor = RGBA(120, 120, 120) }
            onClick { restart() }
        }

        keys.down {
            when (it.key) {
                Key.ENTER, Key.SPACE -> restart()
                else -> Unit
            }
        }
    }

    fun SContainer.restart() {
        map = PositionMap(blocks)
        blocks.values.forEach { it.removeFromParent() }
        blocks.clear()
        score.update(0)
        history.clear()
        generateBlockAndSave()
    }

    fun SContainer.restoreField(history: History.Element) {
        map.forEach { if (it != -1) deleteBlock(it) }
        map = PositionMap(blocks)
        score.update(history.score)
        freeId = 0
        val numbers = history.numberIds.map {
            if (it >= 0 && it < NNumber.entries.size)
                NNumber.entries[it]
            else null
        }
        numbers.forEachIndexed { i, number ->
            if (number != null) {
                val newId = createNewBlock(number, Position(i % 4, i / 4))
                map[i % 4, i / 4] = newId
            }
        }
    }

    fun SContainer.generateBlockAndSave() {
        val position = map.getRandomFreePosition() ?: return
        val number = if (Random.nextDouble() < 0.9) NNumber.ZERO else NNumber.ONE
        val newId = createNewBlock(number, position)
        map[position.x, position.y] = newId
        history.add(map.toNumberIds(), score.value)
    }

    fun SContainer.createNewBlock(number: NNumber, position: Position): Int {
        val id = freeId++
        createNewBlockWithId(id, number, position)
        return id
    }

    fun Container.createNewBlockWithId(id: Int, number: NNumber, position: Position) {
        blocks[id] = block(number).position(columnX(position.x), rowY(position.y))
    }


    fun Container.block(number: NNumber) = Block(number).addTo(this)

    inner class Block(val number: NNumber) : Container() {

        init {
            roundRect(Size(cellSize, cellSize), RectCorners(5f), fill = number.color)
            val textColor = when (number) {
                NNumber.ZERO, NNumber.ONE -> Colors.BLACK
                else -> Colors.WHITE
            }
            text(number.value.toString(), textSizeFor(number), textColor, font).apply {
                centerBetween(0f, 0f, cellSize, cellSize)
            }
        }
    }

    private fun textSizeFor(number: NNumber): Float = when (number) {
        NNumber.ZERO, NNumber.ONE, NNumber.TWO, NNumber.THREE, NNumber.FOUR, NNumber.FIVE -> (cellSize / 2).toFloat()
        NNumber.SIX, NNumber.SEVEN, NNumber.EIGHT -> (cellSize * 4 / 9).toFloat()
        NNumber.NINE, NNumber.TEN, NNumber.ELEVEN, NNumber.TWELVE -> (cellSize * 2 / 5).toFloat()
        NNumber.THIRTEEN, NNumber.FOURTEEN, NNumber.FIFTEEN -> (cellSize * 7 / 20).toFloat()
        NNumber.SIXTEEN -> (cellSize * 3 / 10).toFloat()
    }

}
