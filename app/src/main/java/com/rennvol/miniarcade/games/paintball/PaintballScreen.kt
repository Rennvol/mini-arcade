package com.rennvol.miniarcade.games.paintball

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import com.rennvol.miniarcade.data.Prefs
import com.rennvol.miniarcade.data.dataStore
import com.rennvol.miniarcade.ui.theme.ArcadeTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*
import kotlin.random.Random

private data class Target(var x: Float, var y: Float, var vx: Float, var alive: Boolean = true)
private data class Ball(var x: Float, var y: Float, var vx: Float, var vy: Float, var alive: Boolean = true)

@Composable
fun PaintballScreen(onBack: ()->Unit){
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var chips by remember { mutableIntStateOf(1000) }
    var score by remember { mutableIntStateOf(0) }
    var timeLeft by remember { mutableIntStateOf(30) }
    var over by remember { mutableStateOf(false) }
    var earned by remember { mutableIntStateOf(0) }
    var cw by remember { mutableFloatStateOf(1080f) }
    var ch by remember { mutableFloatStateOf(1600f) }
    var targets by remember { mutableStateOf(listOf<Target>()) }
    var balls by remember { mutableStateOf(listOf<Ball>()) }

    LaunchedEffect(Unit){
        try{ ctx.dataStore.data.collect{ chips = it[Prefs.ARCADE_CHIPS] ?: 1000 } } catch(_:Exception){}
        // init targets
        val t = (0 until 4).map{
            Target(x= Random.nextFloat()*800+60, y= Random.nextFloat()*400+120, vx= (if(Random.nextBoolean()) 5f else -5f))
        }
        targets = t
    }

    fun reset(){
        score=0; timeLeft=30; over=false; earned=0
        balls=listOf()
        targets = (0 until 4).map{ Target(x=Random.nextFloat()*600+60, y=Random.nextFloat()*400+120, vx=if(Random.nextBoolean()) 5f else -5f) }
    }

    fun shoot(tx: Float, ty: Float){
        if(over) return
        val sx = cw/2; val sy = ch - 80f
        val dx = tx - sx; val dy = ty - sy
        val len = sqrt(dx*dx+dy*dy).coerceAtLeast(1f)
        val speed = 22f
        balls = balls + Ball(sx, sy, dx/len*speed, dy/len*speed)
    }

    // game loop
    LaunchedEffect(over){
        while(!over){
            delay(16)
            // move targets
            targets = targets.map{ t ->
                if(!t.alive) t else {
                    var nx = t.x + t.vx
                    var nvx = t.vx
                    if(nx < 30 || nx > cw-70){ nvx = -nvx; nx = t.x + nvx }
                    t.copy(x=nx, vx=nvx)
                }
            }
            // move balls
            val aliveBalls = mutableListOf<Ball>()
            var nScore = score
            for(b in balls){
                if(!b.alive) continue
                b.x += b.vx; b.y += b.vy; b.vy += 0.45f // gravity slight
                if(b.y < -40 || b.y > ch+40 || b.x < -40 || b.x > cw+40) continue
                var hit = false
                for(t in targets){
                    if(!t.alive) continue
                    val dx=b.x - (t.x+26); val dy=b.y - (t.y+26)
                    if(sqrt(dx*dx+dy*dy) < 36f){
                        t.alive=false; hit=true
                        nScore++
                        // per-hit chip reward immediately
                        scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)+1 } } catch(_:Exception){} }
                        break
                    }
                }
                if(!hit) aliveBalls.add(b)
            }
            if(nScore!=score) score=nScore
            // respawn dead targets after delay
            if(targets.count{it.alive} < 3){
                val nt = targets.toMutableList()
                repeat(4 - nt.count{it.alive}){
                    nt.add(Target(x=if(Random.nextBoolean()) 30f else cw-70f, y=Random.nextFloat()*350+100, vx=if(Random.nextBoolean()) 6f else -6f))
                }
                // revive random dead
                for(i in nt.indices) if(!nt[i].alive && Random.nextFloat()<0.02f) nt[i]=nt[i].copy(alive=true, x=Random.nextFloat()*(cw-100)+30, y=Random.nextFloat()*350+100, vx=if(Random.nextBoolean()) 5f else -5f)
                targets = nt.filterIndexed{idx,_ -> idx<6}.let{
                    if(it.count{ t->t.alive }==0) it.mapIndexed{i,v-> if(i==0) v.copy(alive=true) else v} else it
                }
                // simpler: just revive one dead per ~1s
                if(Random.nextFloat()<0.03f){
                    val deadIdx = nt.indexOfFirst{!it.alive}
                    if(deadIdx>=0) nt[deadIdx]=Target(x=Random.nextFloat()*(cw-100)+30, y=Random.nextFloat()*350+100, vx=if(Random.nextBoolean()) 5f else -5f, alive=true)
                    targets = nt
                }
            }
            balls = aliveBalls
        }
    }
    // timer
    LaunchedEffect(over){
        while(!over){
            delay(1000)
            timeLeft--
            if(timeLeft<=0){
                over=true
                earned = score*3
                if(earned>0){ val e=earned; scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)+e } } catch(_:Exception){} } }
            }
        }
    }

    Surface(color=ArcadeTokens.Bg, modifier=Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick=onBack, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.ArrowBack,"back") }
                Text("PAINTBALL", style=MaterialTheme.typography.titleLarge)
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.Surface).padding(horizontal=12.dp, vertical=8.dp)){
                    Text("Chips $chips", style=MaterialTheme.typography.labelLarge, fontWeight=FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.Surface).padding(10.dp), contentAlignment=Alignment.Center){ Text("Score $score", fontWeight=FontWeight.Bold) }
                Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.SurfaceAlt).padding(10.dp), contentAlignment=Alignment.Center){ Text("Time $timeLeft s", color=ArcadeTokens.TextMuted) }
                if(earned>0) Box(Modifier.clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.AccentContainer).padding(horizontal=12.dp, vertical=10.dp)){ Text("+$earned", fontWeight=FontWeight.Bold, fontSize=12.sp) }
            }
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFFDFE6F5))){
                Canvas(modifier=Modifier.fillMaxSize().pointerInput(Unit){ detectTapGestures{ off-> shoot(off.x, off.y) } }){
                    cw=size.width; ch=size.height
                    drawRect(Color(0xFFDFE6F5), size=size)
                    // shooter at bottom center
                    val sx=size.width/2; val sy=size.height-44f
                    drawCircle(Color(0xFF6C5CE7), radius=22f, center=Offset(sx,sy))
                    drawCircle(Color.White, radius=9f, center=Offset(sx,sy-4f))
                    // targets
                    targets.forEach{ t->
                        if(!t.alive) return@forEach
                        drawCircle(Color(0xFFE17055), radius=28f, center=Offset(t.x+26, t.y+26))
                        drawCircle(Color.White, radius=16f, center=Offset(t.x+26, t.y+26))
                        drawCircle(Color(0xFFE17055), radius=8f, center=Offset(t.x+26, t.y+26))
                    }
                    // balls
                    balls.forEach{ b-> drawCircle(Color(0xFF00B894), radius=10f, center=Offset(b.x,b.y)); drawCircle(Color.White.copy(alpha=0.6f), radius=4f, center=Offset(b.x-3,b.y-3)) }
                }
                if(over){
                    Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment=Alignment.Center){
                        Column(horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.clip(RoundedCornerShape(16.dp)).background(ArcadeTokens.Surface).padding(20.dp)){
                            Text("TIME'S UP", fontWeight=FontWeight.Black, fontSize=20.sp)
                            Text("Score $score", fontWeight=FontWeight.Bold)
                            if(earned>0) Text("Earned +$earned chips (score×3)", color=ArcadeTokens.Accent, fontWeight=FontWeight.Bold, fontSize=12.sp)
                            Button(onClick={ reset() }, modifier=Modifier.height(44.dp)){ Text("Play Again") }
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment=Alignment.TopCenter){
                        Text("Tap to shoot • +1 chip per hit • +score×3 on finish", color=ArcadeTokens.TextFaint, fontSize=11.sp)
                    }
                }
            }
        }
    }
}
