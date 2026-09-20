package com.rennvol.miniarcade.games.archer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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

private data class Arrow(var x:Float,var y:Float,var vx:Float,var vy:Float,var alive:Boolean=true, var stuck:Boolean=false)
private data class HitMark(val x:Float,val y:Float,val pts:Int, var life:Float=1f)

@Composable
fun ArcherScreen(onBack:()->Unit){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var chips by remember{ mutableIntStateOf(1000) }
    var totalScore by remember{ mutableIntStateOf(0) }
    var arrowsLeft by remember{ mutableIntStateOf(10) }
    var earned by remember{ mutableIntStateOf(0) }
    var over by remember{ mutableStateOf(false) }
    var cw by remember{ mutableFloatStateOf(1080f) }
    var ch by remember{ mutableFloatStateOf(1600f) }
    var power by remember{ mutableFloatStateOf(0f) } // 0..100
    var angle by remember{ mutableFloatStateOf(18f) } // -20..60
    var isCharging by remember{ mutableStateOf(false) }
    var wind by remember{ mutableFloatStateOf(Random.nextFloat()*6f-3f) }
    var arrows by remember{ mutableStateOf(listOf<Arrow>()) }
    var hitMarks by remember{ mutableStateOf(listOf<HitMark>()) }
    var lastPts by remember{ mutableIntStateOf(0) }
    var lastPtsLife by remember{ mutableFloatStateOf(0f) }
    var targetPulse by remember{ mutableFloatStateOf(0f) }

    LaunchedEffect(Unit){ try{ ctx.dataStore.data.collect{ chips=it[Prefs.ARCADE_CHIPS]?:1000 } }catch(_:Exception){} }

    fun reset(){
        totalScore=0; arrowsLeft=10; earned=0; over=false; power=0f; angle=18f; isCharging=false
        wind=Random.nextFloat()*6f-3f; arrows=listOf(); hitMarks=listOf(); lastPts=0; lastPtsLife=0f
    }

    fun pointsForDist(d:Float):Int{
        return when{
            d < 14f -> 10
            d < 28f -> 8
            d < 42f -> 6
            d < 56f -> 4
            d < 70f -> 2
            else -> 0
        }
    }

    fun releaseArrow(){
        if(arrowsLeft<=0 || over) return
        val bowX=72f; val bowY=ch-88f
        val rad=angle*PI.toFloat()/180f
        val p = power.coerceIn(8f,100f)
        // map power 0..100 to velocity 6..19
        val vel = 6f + p/100f*13f
        val vx = cos(rad)*vel + wind*0.28f
        val vy = -sin(rad)*vel
        arrows=arrows+Arrow(bowX+28f, bowY-6f, vx, vy)
        arrowsLeft--
        power=0f
        isCharging=false
        if(arrowsLeft==0){
            // will check over after arrows settle
        }
    }

    // charge loop
    LaunchedEffect(isCharging){
        while(isCharging && !over && arrowsLeft>0){
            delay(16)
            if(power<100f) power=(power+1.6f).coerceAtMost(100f)
            // wind drifts slowly
            if(Random.nextFloat()<0.01f) wind=(wind+Random.nextFloat()*0.6f-0.3f).coerceIn(-4.5f,4.5f)
        }
    }
    // arrow physics + hit detection
    LaunchedEffect(over, arrowsLeft){
        while(!over){
            delay(16)
            if(arrows.isEmpty() && hitMarks.isNotEmpty()){
                hitMarks=hitMarks.map{ it.copy(life=it.life-0.018f) }.filter{ it.life>0 }
            }
            if(arrows.isEmpty()){
                if(lastPtsLife>0) lastPtsLife=(lastPtsLife-0.02f).coerceAtLeast(0f)
                targetPulse=(targetPulse+0.06f)%(2*PI.toFloat())
                // check round end: no arrows flying and no arrows left
                if(arrowsLeft==0){
                    // wait a bit then finish
                    delay(900)
                    if(arrows.isEmpty()){
                        over=true
                        earned=totalScore/2
                        if(earned>0){ val e=earned; scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)+e } }catch(_:Exception){} } }
                    }
                }
                continue
            }
            val nextArrows=mutableListOf<Arrow>()
            var scoreAdd=0
            var hitPtsTemp=0
            val newMarks=mutableListOf<HitMark>()
            val tx=cw-110f
            val ty=ch*0.44f
            for(a in arrows){
                if(a.stuck){ nextArrows.add(a); continue }
                a.x+=a.vx
                a.y+=a.vy
                a.vy+=0.45f // gravity
                a.vx+=wind*0.006f
                // ground / out of bounds
                if(a.y > ch-22f){
                    a.y=ch-22f; a.stuck=true; a.vx=0f; a.vy=0f
                    // check if near target area ground miss
                    nextArrows.add(a)
                    continue
                }
                if(a.x < -40 || a.x > cw+60 || a.y < -60){
                    // out, drop
                    continue
                }
                // hit target? concentric rings radius 70
                val dx=a.x - tx
                val dy=a.y - ty
                val dist=sqrt(dx*dx+dy*dy)
                if(dist < 70f && a.x > tx-74f){
                    // hit board
                    val pts=pointsForDist(dist)
                    if(pts>0){
                        scoreAdd+=pts
                        hitPtsTemp=pts
                        newMarks.add(HitMark(a.x,a.y,pts))
                        // stick into board
                        a.stuck=true; a.vx=0f; a.vy=0f
                        nextArrows.add(a)
                    } else {
                        // board edge but no points? still stick
                        a.stuck=true; a.vx=0f; a.vy=0f
                        nextArrows.add(a)
                    }
                    continue
                }
                // hit target stand? if x beyond target but y within board height, still count as miss and stick
                if(a.x > tx+74f && abs(a.y-ty)<78f && abs(a.vx)>0.1f){
                    // passed through, maybe hit wall behind - treat as miss
                    // let it continue
                }
                nextArrows.add(a)
            }
            if(scoreAdd>0){
                totalScore+=scoreAdd
                lastPts=scoreAdd
                lastPtsLife=1f
                hitMarks=hitMarks+newMarks
                wind=Random.nextFloat()*6f-3f
            }
            // decay hit marks + lastPts
            hitMarks=(hitMarks+newMarks).takeLast(20)
            if(lastPtsLife>0) lastPtsLife-=0.018f
            arrows=nextArrows.filter{ it.x>-60 && it.x<cw+80 }
            // auto-finish if no arrows left and all stuck/out
            if(arrowsLeft==0 && arrows.all{ it.stuck }){
                // let player see last hit then finish next loop
            }
        }
    }

    Surface(color=ArcadeTokens.Bg, modifier=Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick=onBack, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.ArrowBack,"back") }
                Text("ARCHER", style=MaterialTheme.typography.titleLarge)
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.Surface).padding(horizontal=12.dp, vertical=8.dp)){
                    Text("Chips $chips", style=MaterialTheme.typography.labelLarge, fontWeight=FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.Surface).padding(10.dp), contentAlignment=Alignment.Center){ Text("Score $totalScore", fontWeight=FontWeight.Bold, fontSize=13.sp) }
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.SurfaceAlt).padding(horizontal=12.dp, vertical=10.dp)){ Text("Arrows $arrowsLeft/10", fontWeight=FontWeight.Bold, fontSize=12.sp) }
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if(wind>1.5f||wind<-1.5f) ArcadeTokens.SecondaryContainer else ArcadeTokens.BgMuted).padding(horizontal=10.dp, vertical=10.dp)){
                    Text(if(wind>=0) "Wind → ${"%.1f".format(wind)}" else "Wind ← ${"%.1f".format(-wind)}", fontSize=11.sp, fontWeight=FontWeight.Bold, color=ArcadeTokens.TextMuted)
                }
                if(earned>0) Box(Modifier.clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.AccentContainer).padding(10.dp)){ Text("+$earned", fontWeight=FontWeight.Bold, fontSize=12.sp) }
            }
            // power + angle bar
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically){
                Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.Surface).padding(8.dp)){
                    Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween){ Text("POWER", style=MaterialTheme.typography.labelSmall, color=ArcadeTokens.TextFaint); Text("${power.toInt()}%", fontWeight=FontWeight.Black, fontSize=12.sp, color=when{power>85->ArcadeTokens.Danger; power>60->Color(0xFFF39C12); else->ArcadeTokens.Accent}) }
                        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp)).background(ArcadeTokens.BgMuted)){
                            Box(Modifier.fillMaxWidth(power/100f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Brush.horizontalGradient(listOf(ArcadeTokens.Accent, Color(0xFFFDCB6E), ArcadeTokens.Danger))))
                        }
                    }
                }
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.Surface).padding(horizontal=14.dp, vertical=10.dp), contentAlignment=Alignment.Center){
                    Text("${angle.toInt()}°", fontWeight=FontWeight.Black, fontSize=14.sp)
                }
                if(lastPtsLife>0) Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFFFD700).copy(alpha=0.22f*lastPtsLife)).padding(horizontal=10.dp, vertical=8.dp)){
                    Text("+$lastPts", fontWeight=FontWeight.Black, color=Color(0xFF8A6D00), fontSize=13.sp)
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFFDDE8F5))){
                Canvas(modifier=Modifier.fillMaxSize().pointerInput(Unit){
                    awaitPointerEventScope{
                        while(true){
                            val down=awaitPointerEvent()
                            val pos=down.changes.firstOrNull()?.position ?: continue
                            if(!down.changes.any{ it.pressed }) continue
                            // start charging
                            isCharging=true
                            var startY=pos.y
                            var curAngle=angle
                            // track until release
                            while(true){
                                val ev=awaitPointerEvent()
                                val c=ev.changes.firstOrNull() ?: break
                                if(!c.pressed){
                                    // release
                                    releaseArrow()
                                    break
                                }
                                val dy=c.position.y - startY
                                // drag up = increase angle, drag down = decrease
                                // dy negative (up) -> angle up
                                val deltaAngle = (-dy * 0.22f)
                                angle=(curAngle + deltaAngle).coerceIn(-20f,60f)
                                c.consume()
                            }
                        }
                    }
                }){
                    cw=size.width; ch=size.height
                    // sky gradient
                    drawRect(Brush.verticalGradient(listOf(Color(0xFFDCE9FF), Color(0xFFBFD6FF), Color(0xFFE8F5E9))), size=size)
                    // ground
                    drawRect(Color(0xFF6BAA5A), topLeft=Offset(0f,size.height-28f), size=Size(size.width,28f))
                    drawRect(Color(0xFF8BC34A), topLeft=Offset(0f,size.height-28f), size=Size(size.width,6f))
                    // clouds
                    for(i in 0..3){
                        val cx= (i*260f + 40f) % size.width
                        val cy= 42f + i*12f
                        drawCircle(Color.White.copy(alpha=0.55f), radius=18f, center=Offset(cx,cy))
                        drawCircle(Color.White.copy(alpha=0.55f), radius=14f, center=Offset(cx+16,cy+4))
                        drawCircle(Color.White.copy(alpha=0.55f), radius=12f, center=Offset(cx-14,cy+6))
                    }
                    // target stand + board
                    val tx=size.width-110f; val ty=size.height*0.44f
                    // stand shadow
                    drawRoundRect(Color.Black.copy(alpha=0.12f), topLeft=Offset(tx-76+4, ty-76+5), size=Size(152f,152f), cornerRadius=androidx.compose.ui.geometry.CornerRadius(18f,18f))
                    drawRoundRect(Color(0xFF8D6E63), topLeft=Offset(tx-4, ty+68), size=Size(8f, size.height-ty-68-22f), cornerRadius=androidx.compose.ui.geometry.CornerRadius(4f,4f))
                    // board wood
                    drawCircle(Color(0xFF3E2723), radius=76f, center=Offset(tx,ty))
                    drawCircle(Color(0xFF5D4037), radius=72f, center=Offset(tx,ty))
                    // 5 rings: outermost 70->2pts, 56->4, 42->6, 28->8, 14->10 gold
                    val rings=listOf(
                        Triple(70f, Color(0xFFFFFFFF), 2),
                        Triple(56f, Color(0xFF2C2C2C), 4),
                        Triple(42f, Color(0xFF4FC3F7), 6),
                        Triple(28f, Color(0xFFE53935), 8),
                        Triple(14f, Color(0xFFFFD700), 10)
                    )
                    // draw outer to inner
                    rings.forEach{ (r,col,_) ->
                        drawCircle(col, radius=r, center=Offset(tx,ty))
                        if(r>14f) drawCircle(Color.Black.copy(alpha=0.15f), radius=r, center=Offset(tx,ty), style=Stroke(1.2f))
                    }
                    // gold bullseye highlight
                    drawCircle(Color.White.copy(alpha=0.35f), radius=6f, center=Offset(tx-4,ty-4))
                    if(isCharging){
                        val pulse= (sin(targetPulse)*2f)
                        drawCircle(Color(0xFFFFD700).copy(alpha=0.22f), radius=14f+pulse, center=Offset(tx,ty), style=Stroke(2.2f))
                    }
                    // ring labels small
                    // bow at bottom-left
                    val bowX=72f; val bowY=size.height-88f
                    // bow arc
                    val bowPath=Path().apply{
                        moveTo(bowX, bowY-42f)
                        cubicTo(bowX-18f, bowY-20f, bowX-18f, bowY+12f, bowX, bowY+28f)
                    }
                    drawPath(bowPath, Color(0xFF5D4037), style=Stroke(6f))
                    drawPath(bowPath, Color(0xFF8D6E63), style=Stroke(2.2f))
                    // string (when charging, pulled back)
                    val pull = (power/100f)*28f
                    val strX = bowX - pull
                    drawLine(Color(0xFF3E2723), Offset(bowX, bowY-42f), Offset(strX, bowY-6f), strokeWidth=2f)
                    drawLine(Color(0xFF3E2723), Offset(strX, bowY-6f), Offset(bowX, bowY+28f), strokeWidth=2f)
                    // arrow nocked when charging
                    if(isCharging || arrowsLeft>0){
                        val rad=angle*PI.toFloat()/180f
                        val ax=strX; val ay=bowY-6f
                        val len= 38f + power/100f*10f
                        val ex=ax + cos(rad)*len
                        val ey=ay - sin(rad)*len
                        // shaft
                        drawLine(Color(0xFF3E2723), Offset(ax,ay), Offset(ex,ey), strokeWidth=3.2f)
                        // head
                        val hx=ex; val hy=ey
                        drawPath(Path().apply{
                            moveTo(hx, hy)
                            lineTo(hx - cos(rad+0.45f)*12f, hy + sin(rad+0.45f)*12f)
                            lineTo(hx - cos(rad-0.45f)*12f, hy + sin(rad-0.45f)*12f)
                            close()
                        }, Color(0xFFB0BEC5))
                        drawPath(Path().apply{
                            moveTo(hx, hy)
                            lineTo(hx - cos(rad+0.45f)*12f, hy + sin(rad+0.45f)*12f)
                            lineTo(hx - cos(rad-0.45f)*12f, hy + sin(rad-0.45f)*12f)
                            close()
                        }, Color(0xFF78909C), style=Stroke(1f))
                        // fletching
                        drawLine(Color(0xFFE53935), Offset(ax,ay), Offset(ax - cos(rad)*10f + sin(rad)*5f, ay + sin(rad)*10f + cos(rad)*5f), strokeWidth=2f)
                        drawLine(Color(0xFFE53935), Offset(ax,ay), Offset(ax - cos(rad)*10f - sin(rad)*5f, ay + sin(rad)*10f - cos(rad)*5f), strokeWidth=2f)
                        // trajectory preview dotted line
                        if(power>6f){
                            val vel=6f + power/100f*13f
                            val vx0=cos(rad)*vel + wind*0.28f
                            val vy0=-sin(rad)*vel
                            var px=ax; var py2=ay; var tvx=vx0; var tvy=vy0
                            for(i in 0..26){
                                px+=tvx; py2+=tvy; tvy+=0.45f; tvx+=wind*0.006f
                                if(i%2==0) drawCircle(Color(0xFF1A1A2E).copy(alpha=0.45f), radius=2.6f, center=Offset(px,py2))
                                else drawCircle(Color.White.copy(alpha=0.85f), radius=1.8f, center=Offset(px,py2))
                                if(py2>size.height-22f || px>size.width+20) break
                            }
                        }
                    }
                    // flying / stuck arrows
                    arrows.forEach{ a->
                        val rot = atan2(a.vy, a.vx)
                        val len=34f
                        val ex=a.x + cos(rot)*len/2
                        val ey=a.y + sin(rot)*len/2
                        val sx=a.x - cos(rot)*len/2
                        val sy=a.y - sin(rot)*len/2
                        drawLine(Color(0xFF3E2723), Offset(sx,sy), Offset(ex,ey), strokeWidth=3f)
                        // head
                        drawPath(Path().apply{
                            moveTo(ex, ey)
                            lineTo(ex - cos(rot+0.45f)*10f, ey - sin(rot+0.45f)*10f)
                            lineTo(ex - cos(rot-0.45f)*10f, ey - sin(rot-0.45f)*10f)
                            close()
                        }, Color(0xFF90A4AE))
                        if(a.stuck){
                            drawCircle(Color.Black.copy(alpha=0.18f), radius=4f, center=Offset(sx+2,sy+2))
                        }
                    }
                    // hit marks
                    hitMarks.forEach{ hm->
                        val a=hm.life
                        drawCircle(Color(0xFF1A1A2E).copy(alpha=0.18f*a), radius=5f, center=Offset(hm.x+2, hm.y+2))
                        drawCircle(Color(0xFFFFD700).copy(alpha=a), radius=4f, center=Offset(hm.x, hm.y))
                    }
                    // wind arrow top
                    val wx=size.width/2; val wy=22f
                    drawRoundRect(Color.White.copy(alpha=0.85f), topLeft=Offset(wx-46, wy-10), size=Size(92f,20f), cornerRadius=androidx.compose.ui.geometry.CornerRadius(10f,10f))
                    val wdir = if(wind>=0) 1f else -1f
                    val wlen = abs(wind)*8f + 8f
                    drawLine(Color(0xFF546E7A), Offset(wx - wdir*wlen/2, wy), Offset(wx + wdir*wlen/2, wy), strokeWidth=2.5f)
                    // arrowhead wind
                    val tipX= wx + wdir*wlen/2; val tipY=wy
                    drawPath(Path().apply{
                        moveTo(tipX, tipY)
                        lineTo(tipX - wdir*6f, tipY-4f)
                        lineTo(tipX - wdir*6f, tipY+4f); close()
                    }, Color(0xFF546E7A))
                }
                if(over){
                    Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment=Alignment.Center){
                        Column(horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.clip(RoundedCornerShape(16.dp)).background(ArcadeTokens.Surface).padding(20.dp)){
                            Text("ROUND COMPLETE", fontWeight=FontWeight.Black, fontSize=18.sp)
                            Text("Score $totalScore  •  $arrowsLeft arrows left", fontWeight=FontWeight.Bold)
                            if(earned>0) Text("Earned +$earned chips (score/2)", color=ArcadeTokens.Accent, fontWeight=FontWeight.Bold, fontSize=12.sp)
                            else Text("No points — try again!", color=ArcadeTokens.TextMuted, fontSize=12.sp)
                            Button(onClick={ reset() }, modifier=Modifier.height(44.dp)){ Text("Shoot Again") }
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment=Alignment.BottomCenter){
                        Text("Hold to charge power • Drag up/down for angle • Release to shoot", color=Color(0xFF546E7A).copy(alpha=0.85f), fontSize=10.sp, fontWeight=FontWeight.Bold)
                    }
                }
            }
        }
    }
}
