package com.rennvol.miniarcade.games.flappy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import com.rennvol.miniarcade.data.Prefs
import com.rennvol.miniarcade.data.dataStore
import com.rennvol.miniarcade.ui.theme.ArcadeTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private data class Pipe(var x: Float, var gapY: Float, var passed: Boolean = false)

@Composable
fun FlappyBirdScreen(onBack: ()->Unit){
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var chips by remember{ mutableIntStateOf(1000) }
    var score by remember{ mutableIntStateOf(0) }
    var best by remember{ mutableIntStateOf(0) }
    var earned by remember{ mutableIntStateOf(0) }
    var gameOver by remember{ mutableStateOf(false) }
    var started by remember{ mutableStateOf(false) }
    // bird physics in px (will be set relative to canvas)
    var birdY by remember{ mutableStateOf(400f) }
    var vel by remember{ mutableStateOf(0f) }
    var pipes by remember{ mutableStateOf(listOf<Pipe>()) }
    // canvas size holders
    var cw by remember{ mutableStateOf(1080f) }
    var ch by remember{ mutableStateOf(1920f) }
    val birdX = 220f
    val birdR = 22f
    val pipeW = 72f
    val gapH = 220f
    val groundH = 80f
    val gravity = 1.1f
    val flapImpulse = -16f
    val pipeSpeed = 6f

    LaunchedEffect(Unit){
        try{ ctx.dataStore.data.collect{ chips = it[Prefs.ARCADE_CHIPS] ?: 1000 } } catch(_:Exception){}
    }

    fun reset(){
        birdY = ch/2.5f
        vel = 0f
        pipes = listOf()
        score = 0
        earned = 0
        gameOver = false
        started = false
    }
    fun flap(){
        if(gameOver){ reset(); return }
        if(!started){ started = true }
        vel = flapImpulse
    }
    fun spawnPipe(){
        val minGap = 140f
        val maxGap = ch - groundH - gapH - 140f
        val gy = Random.nextFloat() * (maxGap - minGap) + minGap
        pipes = pipes + Pipe(x = cw + pipeW, gapY = gy)
    }

    // game loop
    LaunchedEffect(started, gameOver, cw, ch){
        var spawnAcc = 0f
        while(true){
            delay(16)
            if(!started || gameOver) continue
            // physics
            vel += gravity
            birdY += vel
            // move pipes
            val np = pipes.map{ it.copy(x = it.x - pipeSpeed) }.toMutableList()
            // spawn
            spawnAcc += pipeSpeed
            if(spawnAcc > 280f){ spawnAcc = 0f; val minGap = 140f; val maxGap = ch - groundH - gapH - 140f; if(maxGap>minGap){ val gy = Random.nextFloat()*(maxGap-minGap)+minGap; np.add(Pipe(cw + pipeW, gy)) } }
            // cull
            val filtered = np.filter{ it.x + pipeW > -20 }
            // score
            var ns = score
            filtered.forEach{ p-> if(!p.passed && p.x + pipeW < birdX - birdR){ p.passed = true; ns++ } }
            if(ns!=score) score = ns
            pipes = filtered
            // collision ground/ceiling
            if(birdY + birdR > ch - groundH || birdY - birdR < 0){
                gameOver = true
                best = maxOf(best, score)
                earned = score * 5
                if(earned>0){ val e=earned; scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS] = (it[Prefs.ARCADE_CHIPS]?:1000)+e } } catch(_:Exception){} } }
                continue
            }
            // pipe collision
            val hit = filtered.any{ p->
                val inX = birdX + birdR > p.x && birdX - birdR < p.x + pipeW
                if(!inX) false else birdY - birdR < p.gapY || birdY + birdR > p.gapY + gapH
            }
            if(hit){
                gameOver = true
                best = maxOf(best, score)
                earned = score * 5
                if(earned>0){ val e=earned; scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS] = (it[Prefs.ARCADE_CHIPS]?:1000)+e } } catch(_:Exception){} } }
            }
        }
    }

    Surface(color=ArcadeTokens.Bg, modifier=Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick=onBack, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.ArrowBack,"back") }
                Text("FLAPPY BIRD", style=MaterialTheme.typography.titleLarge)
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.Surface).padding(horizontal=12.dp, vertical=8.dp)){
                    Text("Chips $chips", style=MaterialTheme.typography.labelLarge, fontWeight=FontWeight.Bold)
                }
            }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.PrimaryContainer).padding(10.dp)){
                Text("Tap to flap • Pass pipes = +1 score • Each point = +5 chips to your arcade pool. Use chips in Poker/Blackjack.", style=MaterialTheme.typography.labelLarge, color=ArcadeTokens.Text, fontSize=12.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.Surface).padding(10.dp), contentAlignment=Alignment.Center){ Text("Score $score", fontWeight=FontWeight.Bold) }
                Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.SurfaceAlt).padding(10.dp), contentAlignment=Alignment.Center){ Text("Best $best", color=ArcadeTokens.TextMuted) }
                if(earned>0) Box(Modifier.clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.AccentContainer).padding(horizontal=12.dp, vertical=10.dp)){ Text("+$earned chips", color=ArcadeTokens.Text, fontWeight=FontWeight.Bold, fontSize=12.sp) }
            }
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFFB3E5FC))){
                Canvas(modifier=Modifier.fillMaxSize().pointerInput(Unit){ detectTapGestures{ flap() } }){
                    cw = size.width
                    ch = size.height
                    // sky
                    drawRect(Color(0xFFB3E5FC), size=size)
                    // pipes
                    pipes.forEach{ p->
                        val col = ArcadeTokens.Accent
                        // top
                        drawRoundRect(color=col, topLeft=Offset(p.x, 0f), size=Size(pipeW, p.gapY), cornerRadius=CornerRadius(10f,10f))
                        // bottom
                        val by = p.gapY + gapH
                        drawRoundRect(color=col, topLeft=Offset(p.x, by), size=Size(pipeW, size.height - by - groundH), cornerRadius=CornerRadius(10f,10f))
                        // caps
                        drawRoundRect(color=Color(0xFF009688), topLeft=Offset(p.x-6f, p.gapY-22f), size=Size(pipeW+12f, 22f), cornerRadius=CornerRadius(6f,6f))
                        drawRoundRect(color=Color(0xFF009688), topLeft=Offset(p.x-6f, by), size=Size(pipeW+12f, 22f), cornerRadius=CornerRadius(6f,6f))
                    }
                    // ground
                    drawRect(Color(0xFFDEB887), topLeft=Offset(0f, size.height - groundH), size=Size(size.width, groundH))
                    drawRect(Color(0xFF8BC34A), topLeft=Offset(0f, size.height - groundH), size=Size(size.width, 16f))
                    // bird
                    val bx = birdX
                    val by = birdY.coerceIn(birdR, size.height - groundH - birdR)
                    drawCircle(color=ArcadeTokens.Secondary, radius=birdR, center=Offset(bx, by))
                    drawCircle(color=Color.White, radius=6f, center=Offset(bx+7f, by-6f))
                    drawCircle(color=Color.Black, radius=3f, center=Offset(bx+8f, by-6f))
                    // beak
                    drawRoundRect(color=ArcadeTokens.Danger, topLeft=Offset(bx+birdR-4f, by-5f), size=Size(14f,10f), cornerRadius=CornerRadius(4f,4f))
                    // wing
                    drawRoundRect(color=Color(0xFFFFCC80), topLeft=Offset(bx-10f, by-2f), size=Size(18f,12f), cornerRadius=CornerRadius(6f,6f))
                    // overlay text
                    if(!started && !gameOver){
                        // handled outside canvas
                    }
                }
                if(!started && !gameOver){
                    Box(Modifier.fillMaxSize(), contentAlignment=Alignment.Center){
                        Column(horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Text("TAP TO FLAP", fontWeight=FontWeight.Black, fontSize=22.sp, color=Color.White)
                            Text("Tap anywhere to start", color=Color.White, fontSize=12.sp)
                        }
                    }
                }
                if(gameOver){
                    Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment=Alignment.Center){
                        Column(horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.clip(RoundedCornerShape(16.dp)).background(ArcadeTokens.Surface).padding(20.dp)){
                            Text("GAME OVER", fontWeight=FontWeight.Black, fontSize=20.sp)
                            Text("Score $score", fontWeight=FontWeight.Bold)
                            if(earned>0) Text("Earned +$earned chips!", color=ArcadeTokens.Accent, fontWeight=FontWeight.Bold)
                            else Text("Score 0 — no chips earned", color=ArcadeTokens.TextMuted, fontSize=12.sp)
                            Button(onClick={ reset() }, modifier=Modifier.height(44.dp)){ Text("Play Again") }
                        }
                    }
                }
            }
            Text("Tip: earn chips here to bet in Poker/Blackjack • Shared pool: arcade_chips", style=MaterialTheme.typography.labelSmall, color=ArcadeTokens.TextFaint)
        }
    }
}
