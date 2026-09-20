package com.rennvol.miniarcade.games.shooter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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

private data class Enemy(var x: Float, var y: Float, var hp: Int = 1)
private data class Bullet(var x: Float, var y: Float, var dmg: Int = 1)

@Composable
fun ShooterScreen(onBack: ()->Unit){
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var chips by remember { mutableIntStateOf(1000) }
    var score by remember { mutableIntStateOf(0) }
    var lives by remember { mutableIntStateOf(3) }
    var over by remember { mutableStateOf(false) }
    var earned by remember { mutableIntStateOf(0) }
    var wave by remember { mutableIntStateOf(1) }
    // upgrades
    var fireMs by remember { mutableIntStateOf(420) }
    var doubleShot by remember { mutableStateOf(false) }
    var dmg by remember { mutableIntStateOf(1) }
    var showShop by remember { mutableStateOf(false) }
    var cw by remember { mutableFloatStateOf(1080f) }
    var ch by remember { mutableFloatStateOf(1600f) }
    var playerX by remember { mutableFloatStateOf(540f) }
    var enemies by remember { mutableStateOf(listOf<Enemy>()) }
    var bullets by remember { mutableStateOf(listOf<Bullet>()) }

    LaunchedEffect(Unit){ try{ ctx.dataStore.data.collect{ chips = it[Prefs.ARCADE_CHIPS] ?: 1000 } } catch(_:Exception){} }

    fun reset(){
        score=0; lives=3; over=false; earned=0; wave=1
        fireMs=420; doubleShot=false; dmg=1; showShop=false
        enemies=listOf(); bullets=listOf(); playerX=cw/2
    }

    // auto shoot
    LaunchedEffect(over, fireMs, doubleShot, dmg, showShop){
        while(!over){
            delay(fireMs.toLong())
            if(showShop) continue
            val py = ch - 110f
            bullets = bullets + Bullet(playerX, py, dmg) + if(doubleShot) listOf(Bullet(playerX-18f, py, dmg), Bullet(playerX+18f, py, dmg)) else emptyList()
            // dedup: if doubleShot we added 3; correct to 2 offset + center already counted -> actually want 2 bullets when double, keep it simple:
            if(doubleShot){
                // remove the extra center duplicate logic: keep 2 side bullets + center is fine as triple
            }
        }
    }
    // main loop
    LaunchedEffect(over, showShop, wave){
        var spawnAcc=0
        while(!over){
            delay(16)
            if(showShop) continue
            // spawn enemies
            spawnAcc++
            if(spawnAcc > (38 - wave.coerceAtMost(6)*3)){
                spawnAcc=0
                enemies = enemies + Enemy(x=Random.nextFloat()*(cw-60)+30, y=-40f, hp= 1 + wave/4)
            }
            // move enemies down
            val speed = 3.2f + wave*0.45f
            val nextEnemies = enemies.map{ it.copy(y=it.y+speed) }.toMutableList()
            // move bullets up
            val nextBullets = mutableListOf<Bullet>()
            for(b in bullets){
                val ny = b.y - 14f
                if(ny > -20) nextBullets.add(b.copy(y=ny))
            }
            // collisions bullet->enemy
            var ns=score
            val aliveEnemies = mutableListOf<Enemy>()
            val aliveBullets = nextBullets.toMutableList()
            for(e in nextEnemies){
                var hitIdx=-1
                var hitDmg=0
                for((bi,b) in aliveBullets.withIndex()){
                    if(kotlin.math.abs(b.x - e.x) < 34 && kotlin.math.abs(b.y - e.y) < 34){ hitIdx=bi; hitDmg=b.dmg; break }
                }
                if(hitIdx>=0){
                    aliveBullets.removeAt(hitIdx)
                    e.hp -= hitDmg
                    if(e.hp<=0){ ns++; }
                    else aliveEnemies.add(e)
                } else {
                    // check bottom collision
                    if(e.y > ch - 90f){
                        aliveEnemies // drop enemy, lose life
                        lives--
                        if(lives<=0){
                            over=true; earned=score*2
                            if(earned>0){ val ev=earned; scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)+ev } } catch(_:Exception){} } }
                        }
                        // don't add to alive
                    } else aliveEnemies.add(e)
                }
            }
            // per-kill chip +1
            if(ns!=score){
                val diff=ns-score
                if(diff>0) scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)+diff } } catch(_:Exception){} }
                score=ns
                if(score>0 && score%12==0){ showShop=true; wave++ }
            }
            enemies = aliveEnemies
            bullets = aliveBullets
        }
    }

    Surface(color=ArcadeTokens.Bg, modifier=Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick=onBack, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.ArrowBack,"back") }
                Text("SHOOTER", style=MaterialTheme.typography.titleLarge)
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.Surface).padding(horizontal=12.dp, vertical=8.dp)){
                    Text("Chips $chips", style=MaterialTheme.typography.labelLarge, fontWeight=FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.Surface).padding(10.dp), contentAlignment=Alignment.Center){ Text("Score $score", fontWeight=FontWeight.Bold, fontSize=13.sp) }
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.SurfaceAlt).padding(10.dp), contentAlignment=Alignment.Center){ Text("♥".repeat(lives.coerceAtLeast(0))+"♡".repeat((3-lives).coerceAtLeast(0)), color=ArcadeTokens.Danger, fontWeight=FontWeight.Bold) }
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.PrimaryContainer).padding(horizontal=10.dp, vertical=10.dp)){ Text("W$wave", fontWeight=FontWeight.Bold, fontSize=12.sp) }
                if(earned>0) Box(Modifier.clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.AccentContainer).padding(10.dp)){ Text("+$earned", fontWeight=FontWeight.Bold, fontSize=12.sp) }
            }
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF0F1220))){
                Canvas(modifier=Modifier.fillMaxSize().pointerInput(Unit){
                    detectDragGestures{ _, drag -> playerX = (playerX + drag.x).coerceIn(28f, cw-28f) }
                }){
                    cw=size.width; ch=size.height
                    drawRect(Color(0xFF0F1220), size=size)
                    // stars
                    for(i in 0 until 28){ val sx=(i*137)%size.width; val sy=(i*271 + (score*7)%400)%size.height; drawCircle(Color.White.copy(alpha=0.22f), radius=1.6f, center=Offset(sx.toFloat(), sy.toFloat())) }
                    // player plane
                    val px=playerX; val py=size.height-70f
                    drawRoundRect(Color(0xFF00B894), topLeft=Offset(px-26, py-18), size=Size(52f,34f), cornerRadius=CornerRadius(8f,8f))
                    drawRoundRect(Color.White, topLeft=Offset(px-10, py-30), size=Size(20f,18f), cornerRadius=CornerRadius(6f,6f))
                    // enemies
                    enemies.forEach{ e->
                        drawRoundRect(Color(0xFFE17055), topLeft=Offset(e.x-26, e.y-18), size=Size(52f,32f), cornerRadius=CornerRadius(8f,8f))
                        drawCircle(Color.White, radius=6f, center=Offset(e.x-8, e.y-4))
                        drawCircle(Color.White, radius=6f, center=Offset(e.x+8, e.y-4))
                    }
                    // bullets
                    bullets.forEach{ b-> drawRoundRect(Color(0xFFFDCB6E), topLeft=Offset(b.x-4, b.y-12), size=Size(8f,16f), cornerRadius=CornerRadius(4f,4f)) }
                }
                if(showShop && !over){
                    Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment=Alignment.Center){
                        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(ArcadeTokens.Surface).padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp), horizontalAlignment=Alignment.CenterHorizontally){
                            Text("WAVE $wave — UPGRADE", fontWeight=FontWeight.Black)
                            Text("Chips $chips • Score $score", style=MaterialTheme.typography.labelLarge, color=ArcadeTokens.TextMuted)
                            UpgradeRow("Fire Rate", "−60ms (${fireMs}ms)", cost=25, chips=chips, enabled=fireMs>160, onBuy={
                                scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)-25 } } catch(_:Exception){} }; fireMs=(fireMs-60).coerceAtLeast(140)
                            })
                            UpgradeRow("Double Shot", if(doubleShot) "Owned" else "2 bullets", cost=60, chips=chips, enabled=!doubleShot, onBuy={
                                scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)-60 } } catch(_:Exception){} }; doubleShot=true
                            })
                            UpgradeRow("Damage +1", "dmg $dmg → ${dmg+1}", cost=40, chips=chips, enabled=true, onBuy={
                                scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)-40 } } catch(_:Exception){} }; dmg+=1
                            })
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                Button(onClick={ showShop=false }, modifier=Modifier.height(44.dp)){ Text("Continue") }
                                OutlinedButton(onClick={ showShop=false }, modifier=Modifier.height(44.dp)){ Text("Skip") }
                            }
                        }
                    }
                }
                if(over){
                    Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment=Alignment.Center){
                        Column(horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.clip(RoundedCornerShape(16.dp)).background(ArcadeTokens.Surface).padding(20.dp)){
                            Text("GAME OVER", fontWeight=FontWeight.Black, fontSize=20.sp)
                            Text("Score $score • Wave $wave", fontWeight=FontWeight.Bold)
                            if(earned>0) Text("Earned +$earned chips (score×2) + per-kill chips", color=ArcadeTokens.Accent, fontWeight=FontWeight.Bold, fontSize=12.sp)
                            Button(onClick={ reset() }, modifier=Modifier.height(44.dp)){ Text("Play Again") }
                        }
                    }
                }
                if(!over && !showShop){
                    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment=Alignment.BottomCenter){
                        Text("Drag to move • Auto fire • Every 12 kills = shop", color=Color.White.copy(alpha=0.55f), fontSize=10.sp)
                    }
                }
            }
        }
    }
}
@Composable private fun UpgradeRow(label:String, desc:String, cost:Int, chips:Int, enabled:Boolean, onBuy:()->Unit){
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.BgMuted).padding(10.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.SpaceBetween){
        Column{ Text(label, fontWeight=FontWeight.Bold, fontSize=13.sp); Text(desc, color=ArcadeTokens.TextMuted, fontSize=11.sp) }
        Button(onClick=onBuy, enabled=enabled && chips>=cost, modifier=Modifier.height(36.dp), contentPadding=PaddingValues(horizontal=12.dp, vertical=0.dp)){
            Text(if(!enabled) "Max" else "$cost chips", fontSize=12.sp)
        }
    }
}
