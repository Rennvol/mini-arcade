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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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

private enum class TargetType(val pts:Int, val radius:Float, val speed:Float, val color:Color, val ring:Color){
    SMALL(10, 20f, 7.2f, Color(0xFFE74C3C), Color(0xFFC0392B)),
    MEDIUM(5, 30f, 4.2f, Color(0xFFF39C12), Color(0xFFD35400)),
    LARGE(3, 42f, 2.1f, Color(0xFF2ECC71), Color(0xFF27AE60))
}
private data class Target(var x:Float, var y:Float, var vx:Float, var type:TargetType, var alive:Boolean=true)
private data class Ball(var x:Float, var y:Float, var vx:Float, var vy:Float, var alive:Boolean=true)
private data class Splat(var x:Float,var y:Float,var r:Float,var col:Color,var life:Float=1f)
private data class Popup(var x:Float,var y:Float,var txt:String,var life:Float=1f, var dy:Float=0f)
private data class Obstacle(val x:Float,val y:Float,val w:Float,val h:Float)

@Composable
fun PaintballScreen(onBack:()->Unit){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var chips by remember{ mutableIntStateOf(1000) }
    var score by remember{ mutableIntStateOf(0) }
    var combo by remember{ mutableIntStateOf(0) }
    var bestCombo by remember{ mutableIntStateOf(0) }
    var lastHitMs by remember{ mutableLongStateOf(0L) }
    var timeLeft by remember{ mutableIntStateOf(45) }
    var over by remember{ mutableStateOf(false) }
    var earned by remember{ mutableIntStateOf(0) }
    var cw by remember{ mutableFloatStateOf(1080f) }
    var ch by remember{ mutableFloatStateOf(1600f) }
    var targets by remember{ mutableStateOf(listOf<Target>()) }
    var balls by remember{ mutableStateOf(listOf<Ball>()) }
    var splats by remember{ mutableStateOf(listOf<Splat>()) }
    var popups by remember{ mutableStateOf(listOf<Popup>()) }
    // obstacles relative - set after cw known
    // obstacles computed in Canvas from cw/ch — no state mutation during draw

    LaunchedEffect(Unit){
        try{ ctx.dataStore.data.collect{ chips=it[Prefs.ARCADE_CHIPS]?:1000 } }catch(_:Exception){}
        targets=(0 until 5).map{ i->
            val t = when(i%3){0->TargetType.SMALL;1->TargetType.MEDIUM;else->TargetType.LARGE}
            Target(x=Random.nextFloat()*700+60, y=Random.nextFloat()*360+90, vx=if(Random.nextBoolean()) t.speed else -t.speed, type=t)
        }
    }

    fun reset(){
        score=0; combo=0; bestCombo=0; timeLeft=45; over=false; earned=0; balls=listOf(); splats=listOf(); popups=listOf()
        targets=(0 until 5).map{ i->
            val t=when(i%3){0->TargetType.SMALL;1->TargetType.MEDIUM;else->TargetType.LARGE}
            Target(x=Random.nextFloat()*700+60, y=Random.nextFloat()*360+90, vx=if(Random.nextBoolean()) t.speed else -t.speed, type=t)
        }
    }

    fun shoot(tx:Float,ty:Float){
        if(over) return
        val sx=cw/2; val sy=ch-72f
        val dx=tx-sx; val dy=ty-sy
        val len=sqrt(dx*dx+dy*dy).coerceAtLeast(1f)
        val speed=23f
        // spread +/- 1.5 deg random
        val spreadRad = (Random.nextFloat()*3f -1.5f) * (PI.toFloat()/180f)
        val c=cos(spreadRad); val s=sin(spreadRad)
        val rdx=dx*c - dy*s
        val rdy=dx*s + dy*c
        balls=balls+Ball(sx,sy, rdx/len*speed, rdy/len*speed)
    }

    // game loop 60fps
    LaunchedEffect(over){
        while(!over){
            delay(16)
            val now=System.currentTimeMillis()
            // combo decay
            if(combo>0 && now-lastHitMs>1800){ combo=0 }
            // move targets
            targets=targets.map{ t->
                if(!t.alive) t else {
                    var nx=t.x+t.vx
                    var nvx=t.vx
                    if(nx < 26f || nx > cw-26f - t.type.radius){ nvx=-nvx; nx=(nx+nvx).coerceIn(26f,cw-26f-t.type.radius) }
                    t.copy(x=nx, vx=nvx)
                }
            }
            // obstacles for collision block: if ball hits obstacle, remove ball + splat on wall
            // move balls
            val aliveBalls=mutableListOf<Ball>()
            var nScore=score
            var nCombo=combo
            var nBest=bestCombo
            val newSplats=mutableListOf<Splat>()
            val newPopups=mutableListOf<Popup>()
            for(b in balls){
                if(!b.alive) continue
                b.x+=b.vx; b.y+=b.vy; b.vy+=0.45f
                if(b.y < -50 || b.y>ch+50 || b.x < -50 || b.x>cw+50) continue
                // obstacle hit (cover positions derived from current canvas size)
                val obsForHit = listOf(
                    Obstacle(cw*0.18f, ch*0.42f, cw*0.28f, 22f),
                    Obstacle(cw*0.58f, ch*0.58f, cw*0.28f, 22f)
                )
                var blocked=false
                for(o in obsForHit){
                    if(b.x>=o.x && b.x<=o.x+o.w && b.y>=o.y && b.y<=o.y+o.h){
                        blocked=true
                        newSplats.add(Splat(b.x,b.y,18f, Color(0xFF8E8E93)))
                        break
                    }
                }
                if(blocked) continue
                var hit=false
                for(t in targets){
                    if(!t.alive) continue
                    val cx=t.x+t.type.radius
                    val cy=t.y+t.type.radius
                    val dx=b.x-cx; val dy=b.y-cy
                    if(sqrt(dx*dx+dy*dy) < t.type.radius+10f){
                        t.alive=false; hit=true
                        val pts=t.type.pts
                        // combo multiplier
                        if(now-lastHitMs<1800) nCombo++ else nCombo=1
                        lastHitMs=now
                        if(nCombo>nBest) nBest=nCombo
                        val mult = 1f + (nCombo-1)*0.5f
                        val gained=(pts*mult).toInt()
                        nScore+=gained
                        newSplats.add(Splat(cx,cy, t.type.radius*1.2f, t.type.color))
                        // extra splat particles
                        repeat(4){ newSplats.add(Splat(cx+Random.nextFloat()*20-10, cy+Random.nextFloat()*20-10, Random.nextFloat()*8+5, t.type.color.copy(alpha=0.7f))) }
                        newPopups.add(Popup(cx, cy-10, "+$gained${if(nCombo>1)" x${"%.1f".format(mult)}" else ""}", life=1f))
                        if(nCombo>1) newPopups.add(Popup(cx, cy-30, "COMBO x$nCombo", life=1f))
                        scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)+1 } }catch(_:Exception){} }
                        break
                    }
                }
                if(!hit) aliveBalls.add(b)
            }
            if(nScore!=score) score=nScore
            if(nCombo!=combo) combo=nCombo
            if(nBest!=bestCombo) bestCombo=nBest
            // fade splats/popups
            splats=(splats+newSplats).map{ it.copy(life=it.life-0.012f) }.filter{ it.life>0 }.takeLast(32)
            popups=(popups+newPopups).map{ it.copy(life=it.life-0.02f, dy=it.dy-0.9f) }.filter{ it.life>0 }.takeLast(16)
            // respawn logic: keep 4-5 alive
            val aliveCnt=targets.count{it.alive}
            if(aliveCnt<4){
                val nt=targets.toMutableList()
                val deadIdx=nt.indexOfFirst{!it.alive}
                if(deadIdx>=0){
                    val t= listOf(TargetType.SMALL, TargetType.MEDIUM, TargetType.LARGE).random()
                    nt[deadIdx]=Target(x=if(Random.nextBoolean()) 30f else cw-60f, y=Random.nextFloat()*360+80, vx=if(Random.nextBoolean()) t.speed else -t.speed, type=t, alive=true)
                } else if(nt.size<6){
                    val t= listOf(TargetType.SMALL, TargetType.MEDIUM, TargetType.LARGE).random()
                    nt.add(Target(x=Random.nextFloat()*(cw-100)+30, y=Random.nextFloat()*360+80, vx=if(Random.nextBoolean()) t.speed else -t.speed, type=t))
                }
                targets=nt
            }
            // slow revive chance
            if(Random.nextFloat()<0.015f){
                val idx=targets.indexOfFirst{!it.alive}
                if(idx>=0){
                    val t=listOf(TargetType.SMALL, TargetType.MEDIUM, TargetType.LARGE).random()
                    val nt=targets.toMutableList()
                    nt[idx]=Target(x=Random.nextFloat()*(cw-120)+40, y=Random.nextFloat()*360+80, vx=if(Random.nextBoolean()) t.speed else -t.speed, type=t, alive=true)
                    targets=nt
                }
            }
            balls=aliveBalls
        }
    }
    // timer
    LaunchedEffect(over){
        while(!over){
            delay(1000)
            timeLeft--
            if(timeLeft<=0){
                over=true
                val bonus=bestCombo*5
                earned=score*3+bonus
                if(earned>0){ val e=earned; scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)+e } }catch(_:Exception){} } }
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
                Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.Surface).padding(10.dp), contentAlignment=Alignment.Center){ Text("Score $score", fontWeight=FontWeight.Bold, fontSize=13.sp) }
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if(combo>1) ArcadeTokens.AccentContainer else ArcadeTokens.SurfaceAlt).padding(horizontal=12.dp, vertical=10.dp), contentAlignment=Alignment.Center){
                    Text(if(combo>1) "COMBO x$combo" else "Combo —", fontWeight=FontWeight.Bold, fontSize=12.sp, color=if(combo>1) ArcadeTokens.Accent else ArcadeTokens.TextMuted)
                }
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if(timeLeft<=10) ArcadeTokens.DangerContainer else ArcadeTokens.BgMuted).padding(horizontal=12.dp, vertical=10.dp), contentAlignment=Alignment.Center){
                    Text("$timeLeft s", fontWeight=FontWeight.Bold, color=if(timeLeft<=10) ArcadeTokens.Danger else ArcadeTokens.Text)
                }
                if(earned>0) Box(Modifier.clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.AccentContainer).padding(horizontal=10.dp, vertical=10.dp)){ Text("+$earned", fontWeight=FontWeight.Bold, fontSize=12.sp) }
            }
            // legend
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(6.dp)){
                LegendChip("S 10pts", Color(0xFFE74C3C)); LegendChip("M 5pts", Color(0xFFF39C12)); LegendChip("L 3pts", Color(0xFF2ECC71))
                Spacer(Modifier.weight(1f))
                Text("Best x$bestCombo", style=MaterialTheme.typography.labelSmall, color=ArcadeTokens.TextFaint)
            }
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFFDDE6F8))){
                Canvas(modifier=Modifier.fillMaxSize().pointerInput(Unit){ detectTapGestures{ off-> shoot(off.x, off.y) } }){
                    cw=size.width; ch=size.height
                    val obstacles = listOf(
                        Obstacle(size.width*0.18f, size.height*0.42f, size.width*0.28f, 22f),
                        Obstacle(size.width*0.58f, size.height*0.58f, size.width*0.28f, 22f)
                    )
                    // field gradient
                    drawRect(Brush.verticalGradient(listOf(Color(0xFFEAF0FF), Color(0xFFD6E4FF), Color(0xFFBFD2F5))), size=size)
                    // subtle grid
                    val gridCol=Color.White.copy(alpha=0.22f)
                    for(i in 0..10){ drawLine(gridCol, Offset(0f, size.height*i/10), Offset(size.width, size.height*i/10), strokeWidth=1f) }
                    for(i in 0..6){ drawLine(gridCol, Offset(size.width*i/6,0f), Offset(size.width*i/6,size.height), strokeWidth=1f) }
                    // obstacles / cover
                    obstacles.forEach{ o->
                        drawRoundRect(Color(0xFF9AA3B8), topLeft=Offset(o.x+3,o.y+4), size=Size(o.w,o.h), cornerRadius=CornerRadius(10f,10f))
                        drawRoundRect(Color(0xFFD0D6E6), topLeft=Offset(o.x,o.y), size=Size(o.w,o.h), cornerRadius=CornerRadius(10f,10f))
                        drawRoundRect(Color(0xFFB8C0D6), topLeft=Offset(o.x,o.y), size=Size(o.w,o.h), cornerRadius=CornerRadius(10f,10f), style=Stroke(2f))
                    }
                    // splats behind targets
                    splats.forEach{ sp->
                        val a=sp.life.coerceIn(0f,1f)
                        drawCircle(Color.Black.copy(alpha=0.14f*a), radius=sp.r*0.9f, center=Offset(sp.x+3, sp.y+4))
                        drawCircle(sp.col.copy(alpha=a), radius=sp.r*a, center=Offset(sp.x, sp.y))
                        drawCircle(Color.White.copy(alpha=0.35f*a), radius=sp.r*0.28f, center=Offset(sp.x-sp.r*0.2f, sp.y-sp.r*0.2f))
                    }
                    // targets premium rings
                    targets.forEach{ t->
                        if(!t.alive) return@forEach
                        val cx=t.x+t.type.radius; val cy=t.y+t.type.radius; val r=t.type.radius
                        // shadow
                        drawCircle(Color.Black.copy(alpha=0.14f), radius=r, center=Offset(cx+3, cy+5))
                        // outer
                        drawCircle(t.type.color, radius=r, center=Offset(cx,cy))
                        // white ring
                        drawCircle(Color.White, radius=r*0.68f, center=Offset(cx,cy))
                        // inner color
                        drawCircle(t.type.ring, radius=r*0.42f, center=Offset(cx,cy))
                        // bullseye dot
                        drawCircle(Color.White, radius=r*0.18f, center=Offset(cx,cy))
                        drawCircle(t.type.color, radius=r*0.08f, center=Offset(cx,cy))
                        // pts label small
                        // highlight
                        drawCircle(Color.White.copy(alpha=0.28f), radius=r*0.32f, center=Offset(cx-r*0.22f, cy-r*0.22f))
                    }
                    // balls (paint)
                    balls.forEach{ b->
                        drawCircle(Color.Black.copy(alpha=0.15f), radius=11f, center=Offset(b.x+2,b.y+3))
                        drawCircle(Color(0xFF6C5CE7), radius=10f, center=Offset(b.x,b.y))
                        drawCircle(Color.White.copy(alpha=0.6f), radius=3.5f, center=Offset(b.x-2.5f,b.y-2.5f))
                    }
                    // shooter bottom center
                    val sx=size.width/2; val sy=size.height-36f
                    drawCircle(Color.Black.copy(alpha=0.15f), radius=26f, center=Offset(sx+2,sy+3))
                    drawCircle(Color(0xFF6C5CE7), radius=24f, center=Offset(sx,sy))
                    drawCircle(Color.White, radius=10f, center=Offset(sx,sy-3f))
                    drawCircle(Color(0xFF6C5CE7), radius=4f, center=Offset(sx,sy-3f))
                }
                // popups overlay via Canvas? use Box overlay text
                Box(Modifier.fillMaxSize()){
                    popups.forEach{ pp->
                        // map canvas coords to Box via absolute offset - approximate using BoxWithConstraints? Keep simple: use Canvas drawn text alternative -> draw via Box positioned
                        // We cheat: draw popup as text at approx position using offset
                        Box(Modifier.offset(x=(pp.x).toDpSafe(cw), y=(pp.y+pp.dy).toDpSafe(ch))){
                            Text(pp.txt, color=Color(0xFF1A1A2E).copy(alpha=pp.life), fontWeight=FontWeight.Black, fontSize=13.sp, modifier=Modifier.background(Color.White.copy(alpha=0.85f*pp.life), RoundedCornerShape(8.dp)).padding(horizontal=6.dp, vertical=2.dp))
                        }
                    }
                }
                if(over){
                    Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment=Alignment.Center){
                        Column(horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.clip(RoundedCornerShape(16.dp)).background(ArcadeTokens.Surface).padding(20.dp)){
                            Text("TIME'S UP", fontWeight=FontWeight.Black, fontSize=20.sp)
                            Text("Score $score  •  Best combo x$bestCombo", fontWeight=FontWeight.Bold)
                            if(earned>0) Text("Earned +$earned chips (score×3 + combo bonus)", color=ArcadeTokens.Accent, fontWeight=FontWeight.Bold, fontSize=12.sp)
                            Button(onClick={ reset() }, modifier=Modifier.height(44.dp)){ Text("Play Again") }
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment=Alignment.TopCenter){
                        Text("Tap to shoot • Small=10 Medium=5 Large=3 • Keep combo!", color=ArcadeTokens.TextFaint, fontSize=11.sp)
                    }
                }
            }
        }
    }
}
@Composable private fun LegendChip(txt:String, col:Color){
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(col.copy(alpha=0.15f)).padding(horizontal=8.dp, vertical=4.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(6.dp)){
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(col))
        Text(txt, fontSize=11.sp, fontWeight=FontWeight.Bold, color=ArcadeTokens.TextMuted)
    }
}
private fun Float.toDpSafe(total:Float): androidx.compose.ui.unit.Dp {
    // cw/ch are px; convert to dp approx: px / density ~3 assumed, but use direct px as dp for overlay - scale down
    return (this/3f).dp
}
