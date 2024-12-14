package com.game.flappberry

import com.littlekt.Context
import com.littlekt.ContextListener
import com.littlekt.async.KtScope
import com.littlekt.async.newSingleThreadAsyncContext
import com.littlekt.file.vfs.readAtlas
import com.littlekt.file.vfs.readAudioClip
import com.littlekt.file.vfs.readBitmapFont
import com.littlekt.graph.node.ui.*
import com.littlekt.graph.sceneGraph
import com.littlekt.graphics.Color
import com.littlekt.graphics.HAlign
import com.littlekt.graphics.VAlign
import com.littlekt.graphics.g2d.*
import com.littlekt.graphics.g2d.shape.ShapeRenderer
import com.littlekt.graphics.webgpu.*
import com.littlekt.input.Key
import com.littlekt.math.MutableVec2f
import com.littlekt.math.Rect
import com.littlekt.math.Vec2f
import com.littlekt.math.geom.Angle
import com.littlekt.math.geom.degrees
import com.littlekt.math.random
import com.littlekt.util.milliseconds
import com.littlekt.util.seconds
import com.littlekt.util.viewport.ExtendViewport
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Duration

class Game(context: Context) : ContextListener(context) {

    override suspend fun Context.start() {
        val atlas = resourcesVfs["atlas.json"].readAtlas()
        val pixelFont = resourcesVfs["fonts/LycheeSoda.fnt"].readBitmapFont()

        val titletexture = atlas.getByPrefix("title").slice
        val gametexture = atlas.getByPrefix("game_over").slice

        val audioCtx = newSingleThreadAsyncContext()
        val jumpSfx = resourcesVfs["sounds/jump.wav"].readAudioClip()
        val hitSfx = resourcesVfs["sounds/hit.wav"].readAudioClip()
        val scoreSfx = resourcesVfs["sounds/score.wav"].readAudioClip()
        val confettiSfx = resourcesVfs["sounds/confetti.wav"].readAudioClip()

        val device = graphics.device
        val surfaceCapabilities = graphics.surfaceCapabilities
        val preferredFormat = graphics.preferredFormat
        val batch = SpriteBatch(device, graphics, preferredFormat)
        val shapeRenderer = ShapeRenderer(batch)
        val viewportGame = ExtendViewport(120, 420)
        val cameraGame = viewportGame.camera
        val fx = Particles(atlas)

        cameraGame.zoom = 1.2f

        var debug = false
        var title = true
        var pause = false
        var gameOver = false
        var score = 0
        var best = 0

        val player = Player(atlas.getByPrefix("strawberry").slice, 16f, 16f, fx = fx).apply {
            position.set(0f, 0f)
        }
        val pipes = mutableListOf<Pipe>()
        val backgrounds = listOf(
            Background(
                atlas.getByPrefix("background_2").slice,
                MutableVec2f(-viewportGame.virtualWidth / 2, -viewportGame.virtualHeight / 2f),
                MutableVec2f(0.025f, 0f)
            ),
            Background(
                atlas.getByPrefix("background_1").slice,
                MutableVec2f(-viewportGame.virtualWidth / 2, -viewportGame.virtualHeight / 1.5f),
                MutableVec2f(0.05f, 0f)
            ),
            Background(
                atlas.getByPrefix("background_0").slice,
                MutableVec2f(-viewportGame.virtualWidth / 2, -viewportGame.virtualHeight / 1.3f),
                MutableVec2f(0.1f, 0f)
            )
        )
        val grounds = listOf(
            Ground(atlas.getByPrefix("ground").slice, true).apply {
                position.set(-viewportGame.virtualWidth / 2, 200f)
            },

            Ground(atlas.getByPrefix("ground").slice, false).apply {
                position.set(-viewportGame.virtualWidth / 2, -260f)
            }
        )

        var timer = 0f
        val speed = -0.1f
        val maxTime = 2000f

        fun startGame()
        {
            title = false
        }

        fun resetGame()
        {
            score = 0
            timer = 0f
            gameOver = false

            pipes.clear()

            player.position.set(0f,0f)
            player.reset()
        }

        fun saveScore()
        {
            best = kvStorage.loadString("score")?.toInt() ?: 0

            if(score > best) {
                kvStorage.store("score", score.toString())
                KtScope.launch(audioCtx) { confettiSfx.play(1f) }
            }
        }

        val ui = sceneGraph(this, ExtendViewport(136, 256))
        {
            vBoxContainer()
            {
                separation = 80
                anchorRight = 1f
                anchorTop = 1f
                marginTop = -50f
                marginLeft = -15f

                textureRect()
                {
                    var timer = 0f
                    slice = titletexture
                    stretchMode = TextureRect.StretchMode.KEEP_CENTERED
                    onUpdate += {
                        timer += 0.01f * dt.milliseconds
                        marginBottom = 160 + 5 * sin(timer/2)
                    }
                }

                label()
                {
                    horizontalAlign = HAlign.CENTER
                    verticalAlign = VAlign.CENTER
                    font = pixelFont
                    text = "Space to play"
                }

                onUpdate += {
                    visible = title
                }

                onInput += {
                    if(it.key == Key.SPACE && visible)
                    {
                        startGame()
                    }
                }
            }

            vBoxContainer() {
                separation = 10
                marginBottom = 5f
                anchorRight = 1f
                anchorTop = 1f

                label() {
                    font = pixelFont
                    horizontalAlign = HAlign.CENTER

                    onUpdate += {
                        text = "$score"
                        visible = !title && !gameOver
                    }
                }
            }

            vBoxContainer()
            {
                var timer = 0f
                separation = 80
                anchorRight = 1f
                anchorTop = 1f
                marginTop = -50f
                marginLeft = -15f

                textureRect()
                {
                    var timer = 0f
                    slice = gametexture
                    stretchMode = TextureRect.StretchMode.KEEP_CENTERED
                    onUpdate += {
                        timer += 0.01f * dt.milliseconds
                        marginBottom = 160 + 5 * sin(timer/2)
                    }
                }

                label()
                {
                    horizontalAlign = HAlign.CENTER
                    verticalAlign = VAlign.CENTER
                    font = pixelFont

                    onUpdate += {
                        text = if(score > best) "New Best Score: $score" else "Best Score: $best"

                        if(Random.nextFloat() < 0.08f && visible && score > best)
                        {
                            fx.confetti((-viewportGame.virtualWidth/2 .. viewportGame.virtualWidth/2).random(), (-viewportGame.virtualHeight/2 .. viewportGame.virtualHeight/2).random())
                        }
                    }
                }

                label()
                {
                    horizontalAlign = HAlign.CENTER
                    verticalAlign = VAlign.CENTER
                    font = pixelFont
                    text = "Space to play"
                }

                onUpdate += {
                    visible = gameOver

                    if(visible)
                    {
                        timer += dt.milliseconds
                    }
                }

                onInput += {
                    if(it.key == Key.SPACE && visible && timer > 500)
                    {
                        resetGame()
                        timer = 0f
                    }
                }
            }

        }.also { it.initialize() }

        fun logic(dt: Duration) {
            run pipeGeneration@
            {
                if (gameOver) return@pipeGeneration

                timer += dt.milliseconds

                if (timer > maxTime) {
                    timer -= maxTime

                    val doublePipe = Random.nextBoolean()

                    if (doublePipe) {
                        val bottom = min(max(Random.nextInt(3, 7), 3), 7)
                        val top = 8 - (bottom - (3..4).random())

                        pipes.add(
                            Pipe(
                                atlas.getByPrefix("pipe_1").slice,
                                atlas.getByPrefix("pipe_0").slice,
                                top.toInt()
                            ).apply
                            {
                                velocity.set(speed, 0f)
                                position.set(cameraGame.virtualWidth + width, 0f)

                                hidden = false
                                flip = true

                                generate()
                            })

                        pipes.add(
                            Pipe(
                                atlas.getByPrefix("pipe_1").slice,
                                atlas.getByPrefix("pipe_0").slice,
                                bottom
                            ).apply
                            {
                                velocity.set(speed, 0f)
                                position.set(cameraGame.virtualWidth + width, 0f)

                                hidden = true
                                flip = false

                                generate()
                            })
                    } else {
                        pipes.add(
                            Pipe(
                                atlas.getByPrefix("pipe_1").slice,
                                atlas.getByPrefix("pipe_0").slice,
                                min(max(Random.nextInt(7, 10), 7), 10)
                            ).apply
                            {
                                velocity.set(speed, 0f)
                                position.set(cameraGame.virtualWidth + width, 0f)
                                flip = Random.nextBoolean()

                                generate()
                            })
                    }
                }
            }

            run pipeUpdate@
            {
                if (gameOver) return@pipeUpdate

                for (pipe in pipes) {
                    pipe.update(dt)

                    if (pipe.position.x + pipe.width * 3 < viewportGame.x - viewportGame.virtualWidth / 2) pipe.removed =
                        true
                }

                pipes.filter { it.removed }.forEach { pipes.remove(it) }
            }

            run pipeCollide@
            {
                if (pipes.isEmpty() || gameOver) return@pipeCollide

                for (pipe in pipes) {
                    if (player.isCollide(pipe.rect) && !gameOver) {
                        KtScope.launch(audioCtx) { hitSfx.play(1f) }
                        player.hit()
                        saveScore()
                        gameOver = true
                    }

                    if (!player.hitted && pipe.position.x + pipe.width < player.position.x && !pipe.hidden && !pipe.collected) {
                        KtScope.launch(audioCtx) { scoreSfx.play(1f) }
                        score++
                        pipe.collected = true
                    }
                }
            }

            run groundUpdate@
            {
                if (gameOver) return@groundUpdate

                for (ground in grounds) {
                    ground.update(dt)
                }
            }

            run groundCollide@
            {
                for (ground in grounds) {
                    if (player.isCollide(ground.rect) && !player.hitted) {
                        KtScope.launch(audioCtx) { hitSfx.play(1f) }
                        player.hit()
                        saveScore()
                        gameOver = true
                    }
                }
            }

            run updateBackground@
            {
                if (gameOver) return@updateBackground

                for (back in backgrounds) {
                    back.update(dt)
                }
            }

            if (player.position.y > viewportGame.y - viewportGame.height / 2) {
                player.update(dt)
            }

            if (!gameOver) {
                if (context.input.isKeyJustPressed(Key.SPACE)) {
                    player.jump(1f)
                    KtScope.launch(audioCtx) { jumpSfx.play(1f) }
                }
            }
        }

        onResize { width, height ->
            viewportGame.update(width, height)
            ui.resize(width, height)
            graphics.configureSurface(
                TextureUsage.RENDER_ATTACHMENT,
                preferredFormat,
                PresentMode.FIFO,
                surfaceCapabilities.alphaModes[0]
            )
        }

        onUpdate { dt ->

            if (input.isKeyJustPressed(Key.P) && !title && !gameOver)
            {
                pause = !pause
            }

            if (!pause && !title) logic(dt)

            cameraGame.update()
            fx.update(dt)

            val surfaceTexture = graphics.surface.getCurrentTexture()
            when (val status = surfaceTexture.status) {
                TextureStatus.SUCCESS -> {
                    // all good, could check for `surfaceTexture.suboptimal` here.
                }

                TextureStatus.TIMEOUT, TextureStatus.OUTDATED, TextureStatus.LOST -> {
                    surfaceTexture.texture?.release()
                    logger.info { "getCurrentTexture status=$status" }
                    return@onUpdate
                }

                else -> {
                    // fatal
                    logger.fatal { "getCurrentTexture status=$status" }
                    close()
                    return@onUpdate
                }
            }

            val swapChainTexture = checkNotNull(surfaceTexture.texture)
            val frame = swapChainTexture.createView()
            val commandEncoder = device.createCommandEncoder()
            val renderPassEncoder =
                commandEncoder.beginRenderPass(
                    desc =
                        RenderPassDescriptor(
                            listOf(
                                RenderPassColorAttachmentDescriptor(
                                    view = frame,
                                    loadOp = LoadOp.CLEAR,
                                    storeOp = StoreOp.STORE,
                                    clearColor =
                                        if (preferredFormat.srgb) Color.DARK_GRAY.toLinear()
                                        else Color.DARK_GRAY
                                )
                            )
                        )
                )

            batch.use(renderPassEncoder, cameraGame.viewProjection)
            {
                for (back in backgrounds) {
                    back.draw(batch, viewportGame)
                }

                for (pipe in pipes) {
                    pipe.draw(batch)
                }

                for (ground in grounds) {
                    ground.draw(batch, viewportGame)
                }

                player.render(batch)
                fx.render(batch)


                if (debug) {
                    for (back in backgrounds) {
                        back.debug(shapeRenderer)
                    }

                    for (pipe in pipes) {
                        pipe.debug(shapeRenderer)
                    }

                    for (ground in grounds) {
                        ground.debug(shapeRenderer)
                    }

                    player.debug(shapeRenderer)
                }
            }
            renderPassEncoder.end()

            val renderPassDescriptor = RenderPassDescriptor(
                listOf(
                    RenderPassColorAttachmentDescriptor(
                        view = frame,
                        loadOp = LoadOp.LOAD,
                        storeOp = StoreOp.STORE
                    )
                ), label = "Scene Graph render pass"
            )


            ui.update(dt)
            ui.render(commandEncoder, renderPassDescriptor)


            val commandBuffer = commandEncoder.finish()

            device.queue.submit(commandBuffer)
            graphics.surface.present()

            commandBuffer.release()
            renderPassEncoder.release()
            commandEncoder.release()
            frame.release()
            swapChainTexture.release()
        }

        onPostUpdate {
            if (input.isKeyJustPressed(Key.F1)) debug = !debug
        }

        onRelease {
            atlas.release()
            batch.release()
            device.release()
        }
    }
}

class Player(
    private val sprite: TextureSlice,
    private val with: Float,
    private val height: Float,
    private val fx: Particles
) {
    val position = MutableVec2f(0f, 0f)
    var gravityFactor = 1f
    var frictionFactor = 1f
    var frames = 0f
    var hitted = false

    private val velocity = MutableVec2f(0f, 0f)
    private val friction = 0.9f
    private val gravity = 0.02f
    private val jump = 0.4f
    private var angularVelocity = Angle.ZERO
    private var angle = Angle.ZERO
    private val offsetX = -sprite.width / 2 + with / 2
    private val offsetY = -sprite.height / 2 + height / 2

    val rect = Rect(0f, 0f, with, height)

    fun reset() {
        gravityFactor = 1f
        frictionFactor = 1f
        angularVelocity = Angle.ZERO
        angle = Angle.ZERO
        frames = 100f
        velocity.set(0f, 0f)
        hitted = false
    }

    fun update(dt: Duration) {
        angularVelocity *= (friction * frictionFactor)
        velocity.y -= gravity * gravityFactor

        position.x += velocity.x * dt.milliseconds
        position.y += velocity.y * dt.milliseconds
        angle += (angularVelocity.degrees * dt.milliseconds).degrees

        rect.set(position.x + offsetX, position.y + offsetY, with, height)

        if (hitted && Random.nextFloat() < 0.3f) {
            fx.trail(position.x, position.y)
        }

        if(frames > 0) frames -= dt.milliseconds
    }

    fun render(batch: SpriteBatch) {
        batch.draw(
            sprite,
            position.x,
            position.y,
            originX = sprite.width / 2f,
            originY = sprite.height / 2f,
            rotation = angle
        )
    }

    fun debug(shapeRenderer: ShapeRenderer) {
        shapeRenderer.rectangle(rect)
    }

    fun jump(force: Float) {
        velocity.y = jump * force
        angularVelocity -= 2.5.degrees

        val future = future()
        fx.flap(future.x, future.y)
    }

    fun isCollide(other: Rect): Boolean {
        if(frames > 0) return false

        return other.intersects(rect.x, rect.y, rect.x2, rect.y2)
    }

    fun hit() {
        hitted = true;

        fx.explode(position.x, position.y)

        velocity.x = 0.1f
        velocity.y = 0.4f;
        angularVelocity = 100f.degrees
    }

    private fun future(): Vec2f {
        return Vec2f(position.x + velocity.x, position.y + velocity.y)
    }
}

class Background(private val sprite: TextureSlice, val position: MutableVec2f, val velocity: MutableVec2f) :
    GameObject() {
    private var offsetX = 0f;

    override fun update(dt: Duration) {
        if (offsetX > sprite.width) {
            offsetX = 0f;
        }

        offsetX += velocity.x * dt.milliseconds
    }

    fun draw(batch: SpriteBatch, viewport: ExtendViewport) {
        for (i in 0 until viewport.width / sprite.width + 2) {
            batch.draw(
                sprite,
                (-viewport.width / 2 + (i * (sprite.width - 0.1))).toFloat(),
                position.y,
                originX = offsetX,
            )
        }
    }
}

class Ground(private val sprite: TextureSlice, private val flip: Boolean) : Obstacle(
    width = sprite.width.toFloat() * 3,
    height = sprite.height.toFloat(),
    position = MutableVec2f(0f, 0f),
    velocity = MutableVec2f(0.1f, 0f)
) {

    private var offsetX = 0f;

    val rect = Rect(0f, 0f, width, height)

    override fun update(dt: Duration) {
        if (offsetX > sprite.width * 3) {
            offsetX = 0f;
        }

        offsetX += velocity.x * dt.milliseconds

        rect.set(position.x - sprite.width / 2, position.y, width, height)
    }

    fun draw(batch: SpriteBatch, viewport: ExtendViewport) {
        for (i in 0 until viewport.width / sprite.width + 2) {
            batch.draw(
                sprite,
                (-viewport.width / 2 + (i * (sprite.width - 0.1))).toFloat(),
                position.y,
                flipY = flip,
                originX = offsetX
            )
        }
    }

    override fun debug(shapeRenderer: ShapeRenderer) {
        shapeRenderer.rectangle(rect)
    }
}

class Pipe(
    private val body: TextureSlice,
    private val head: TextureSlice,
    private val size: Int
) : Obstacle(
    width = body.width.toFloat() / 1.8f,
    height = body.height.toFloat() * size,
    position = MutableVec2f(),
    velocity = MutableVec2f()
) {
    private val offsetX = width * 0.4f
    val rect = Rect(0f, 0f, width, height)
    var flip = false
    var hidden = false
    var collected = false

    fun generate() {
        position.y = if (flip) 220f - height else -220f
    }

    override fun update(dt: Duration) {
        position.x += velocity.x * dt.milliseconds

        rect.set(position.x, position.y, width, height)
    }

    override fun draw(batch: SpriteBatch) {
        val dir = if (flip) 0 else 1

        batch.draw(head, position.x, position.y + (size - 1) * dir * body.height, flipY = flip, originX = offsetX)

        for (i in 0 until size - 1) {
            batch.draw(body, position.x, position.y + (i + 1 - dir) * body.height, originX = offsetX)
        }
    }

    override fun debug(shapeRenderer: ShapeRenderer) {
        shapeRenderer.rectangle(rect)
    }
}

open class GameObject {
    var removed: Boolean = false

    open fun update(dt: Duration) {

    }

    open fun draw(batch: SpriteBatch) {

    }

    open fun debug(shapeRenderer: ShapeRenderer) {

    }
}

open class Obstacle(val position: MutableVec2f, val velocity: MutableVec2f, val width: Float, val height: Float) :
    GameObject()

class Particles(private val atlas: TextureAtlas) {
    private val system = ParticleSimulator(2048)

    private fun alloc(slice: TextureSlice, x: Float, y: Float) = system.alloc(slice, x, y)

    private fun create(num: Int, createParticle: (index: Int) -> Unit) {
        for (i in 0 until num) createParticle(i)
    }

    fun render(batch: SpriteBatch) = system.draw(batch)

    fun update(dt: Duration) = system.update(dt)

    fun flap(x: Float, y: Float) {
        create(4) {
            alloc(atlas.getByPrefix("particle_0").slice, x, y).apply {
                scale((0.1f..1f).random())
                gravityX = -(0.05f..0.2f).random()
                gravityY = -(0.1f..0.3f).random()
                friction = (0.9f..0.99f).random()
                life = (0.5f..0.8f).random().seconds
            }
        }
    }

    fun explode(x: Float, y: Float) {
        create(10)
        {
            alloc(atlas.getByPrefix("particle_0").slice, x, y).apply {
                gravityX = (-0.1f..0.1f).random()
                gravityY = -(0.1f..0.3f).random()
                friction = (0.9f..0.99f).random()
                life = (0.5f..2f).random().seconds
            }
        }
    }

    fun trail(x: Float, y: Float) {
        create(2)
        {
            alloc(atlas.getByPrefix("particle_1").slice, x, y).apply {
                life = (0.1f..0.5f).random().seconds
                scale((0.1f..1f).random())
            }
        }
    }

    fun confetti(x: Float, y: Float) {
        create(5)
        {
            alloc(atlas.getByPrefix("particle_1").slice, x, y).apply {
                scale((1f..2f).random())
                gravityX = (-0.1f..0.1f).random()
                gravityY = -(0.1f..0.3f).random()
                friction = (0.9f..0.99f).random()
                life = (0.5f..1f).random().seconds

                color.set((0f..1f).random(), (0f..1f).random(), (0f..1f).random(), 1f)
            }
        }
    }
}