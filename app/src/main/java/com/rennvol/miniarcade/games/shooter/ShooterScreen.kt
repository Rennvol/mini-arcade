package com.rennvol.miniarcade.games.shooter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

private enum class EType(val hp:Int, val speedMul:Float, val w:Float, val h:Float, val col:Color){
    SMALL(1, 1.45f, 40f, 26f, Color(0xFFFF7675)),
    MEDIUM(2, 1.0f, 52f, 32f, Color(0xFFFDCB6E)),
    TANK(5, 0.62f, 64f, 40f, Color(0xFF636E72))
}
private data class Enemy(var x:Float, var y:Float, var hp:Int, var maxHp:Int, var type:EType, var isBoss:Boolean=false)
private data class Bullet(var x:Float, var y:Float, var dmg:Int, var pierce:Boolean=false, var homing:Boolean=false)
private data class BossBullet(var x:Float, var y:Float)

@Composable
fun ShooterScreen(onBack:()->Unit){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var chips by remember{ mutableIntStateOf(1000) }
    var score by remember{ mutableIntStateOf(0) }
    var lives by remember{ mutableIntStateOf(3) }
    var over by remember{ mutableStateOf(false) }
    var earned by remember{ mutableIntStateOf(0) }
    var wave by remember{ mutableIntStateOf(1) }
    // upgrades
    var fireMs by remember{ mutableIntStateOf(420) }
    var doubleShot by remember{ mutableStateOf(false) }
    var tripleShot by remember{ mutableStateOf(false) }
    var dmg by remember{ mutableIntStateOf(1) }
    var pierce by remember{ mutableStateOf(false) }
    var speedBoost by remember{ mutableStateOf(false) }
    var homing by remember{ mutableStateOf(false) }
    var bombs by remember{ mutableIntStateOf(0) }
    var showShop by remember{ mutableStateOf(false) }
    var cw by remember{ mutableFloatStateOf(1080f) }
    var ch by remember{ mutableFloatStateOf(1600f) }
    var playerX by remember{ mutableFloatStateOf(540f) }
    var enemies by remember{ mutableStateOf(listOf<Enemy>()) }
    var bullets by remember{ mutableStateOf(listOf<Bullet>()) }
    var bossBullets by remember{ mutableStateOf(listOf<BossBullet>()) }
    var shake by remember{ mutableFloatStateOf(0f) }

    LaunchedEffect(Unit){ try{ ctx.dataStore.data.collect{ chips=it[Prefs.ARCADE_CHIPS]?:1000 } }catch(_:Exception){} }

    fun isBossWave(w:Int)= w%5==0
    fun bossHp(w:Int)= 20 + w*5

    fun reset(){
        score=0; lives=3; over=false; earned=0; wave=1
        fireMs=420; doubleShot=false; tripleShot=false; dmg=1; pierce=false; speedBoost=false; homing=false; bombs=0; showShop=false
        enemies=listOf(); bullets=listOf(); bossBullets=listOf(); playerX=cw/2
    }

    fun doBomb(){
        if(bombs<=0) return
        bombs--
        val isBoss= enemies.any{ it.isBoss }
        if(isBoss){
            // damage boss heavily
            enemies=enemies.map{ if(it.isBoss) it.copy(hp= it.hp - 12) else it }.filter{ it.hp>0 }
            if(enemies.none{ it.isBoss }){
                score+=20; scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)+20 } }catch(_:Exception){} }
            }
        } else {
            val cleared=enemies.size
            if(cleared>0){
                score+=cleared
                scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)+cleared } }catch(_:Exception){} }
            }
            enemies=listOf()
        }
        shake=10f
    }

    // auto shoot
    LaunchedEffect(over, fireMs, doubleShot, tripleShot, dmg, pierce, homing, showShop){
        while(!over){
            delay(fireMs.toLong())
            if(showShop) continue
            val py=ch-110f
            val baseDmg=dmg
            val isPierce=pierce
            val isHoming=homing
            val newBullets = mutableListOf<Bullet>()
            if(tripleShot){
                newBullets.add(Bullet(playerX-18f, py, baseDmg, isPierce, isHoming))
                newBullets.add(Bullet(playerX, py, baseDmg, isPierce, isHoming))
                newBullets.add(Bullet(playerX+18f, py, baseDmg, isPierce, isHoming))
            } else if(doubleShot){
                newBullets.add(Bullet(playerX-14f, py, baseDmg, isPierce, isHoming))
                newBullets.add(Bullet(playerX+14f, py, baseDmg, isPierce, isHoming))
            } else {
                newBullets.add(Bullet(playerX, py, baseDmg, isPierce, isHoming))
            }
            bullets=bullets+newBullets
        }
    }
    // boss shooting
    LaunchedEffect(over, showShop, wave, enemies){
        while(!over){
            delay(900)
            if(showShop) continue
            val boss=enemies.firstOrNull{ it.isBoss } ?: continue
            bossBullets=bossBullets+BossBullet(boss.x, boss.y+30f)
        }
    }
    // main loop
    LaunchedEffect(over, showShop, wave){
        var spawnAcc=0
        var bossSpawnedForWave=false
        while(!over){
            delay(16)
            if(showShop){ bossSpawnedForWave=false; continue }
            if(shake>0) shake=(shake-0.8f).coerceAtLeast(0f)
            // handle wave boss spawn
            if(isBossWave(wave) && enemies.none{ it.isBoss } && !bossSpawnedForWave){
                // spawn boss centered top
                val hp=bossHp(wave)
                enemies=enemies+Enemy(x=cw/2, y=80f, hp=hp, maxHp=hp, type=EType.TANK, isBoss=true)
                bossSpawnedForWave=true
            }
            // spawn normal enemies only if not boss wave active boss alive
            val bossAlive=enemies.any{ it.isBoss }
            if(!bossAlive){
                if(bossSpawnedForWave) bossSpawnedForWave=false
                spawnAcc++
                val threshold = 38 - wave.coerceAtMost(6)*3
                if(spawnAcc > threshold){
                    spawnAcc=0
                    val t = when(Random.nextInt(100)){
                        in 0..44 -> EType.SMALL
                        in 45..79 -> EType.MEDIUM
                        else -> EType.TANK
                    }
                    val hp = t.hp + wave/5
                    enemies=enemies+Enemy(x=Random.nextFloat()*(cw-70)+35, y=-40f, hp=hp, maxHp=hp, type=t)
                }
            } else {
                // boss movement: sway horizontally
                enemies=enemies.map{ e->
                    if(e.isBoss) e.copy(x= e.x + sin(System.currentTimeMillis()/520.0).toFloat()*1.6f) else e
                }
            }
            val baseSpeed = 3.2f + wave*0.45f
            val nextEnemies = enemies.map{ e->
                if(e.isBoss) e else e.copy(y=e.y + baseSpeed*e.type.speedMul)
            }.toMutableList()

            // homing update + move bullets
            val nextBullets = mutableListOf<Bullet>()
            for(b in bullets){
                var nx=b.x; var ny=b.y - (if(speedBoost) 16f else 14f)
                if(b.homing && enemies.isNotEmpty()){
                    val target=enemies.minByOrNull{ abs(it.x - b.x) + abs(it.y - b.y) }
                    if(target!=null){
                        val dx=target.x - b.x
                        nx += (dx*0.08f).coerceIn(-3.5f,3.5f)
                    }
                }
                if(ny > -30) nextBullets.add(b.copy(x=nx, y=ny))
            }
            // move boss bullets down
            val nextBossBullets = bossBullets.map{ it.copy(y=it.y+7f) }.filter{ it.y < ch+20 }

            // check boss bullet hit player
            var hitPlayer=false
            for(bb in nextBossBullets){
                if(abs(bb.x - playerX) < 28 && abs(bb.y - (ch-70f)) < 28){
                    hitPlayer=true; break
                }
            }
            if(hitPlayer){
                lives--
                bossBullets=nextBossBullets.filterNot{ abs(it.x-playerX)<28 && abs(it.y-(ch-70f))<28 }
                shake=8f
                if(lives<=0){
                    over=true; earned=score*2
                    if(earned>0){ val ev=earned; scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)+ev } }catch(_:Exception){} } }
                }
            } else {
                bossBullets=nextBossBullets
            }

            // collisions bullet->enemy
            var ns=score
            var bonusChips=0
            var bossKilled=false
            val aliveEnemies = mutableListOf<Enemy>()
            val aliveBullets = nextBullets.toMutableList()
            val toRemoveBulletIdx = mutableSetOf<Int>()
            for(e in nextEnemies){
                var curHp=e.hp
                var hitCount=0
                // check all bullets that overlap this enemy
                for((bi,b) in aliveBullets.withIndex()){
                    if(bi in toRemoveBulletIdx) continue
                    val ew= if(e.isBoss) 92f else e.type.w
                    val eh= if(e.isBoss) 58f else e.type.h
                    if(abs(b.x - e.x) < ew/2+6 && abs(b.y - e.y) < eh/2+8){
                        curHp -= b.dmg
                        hitCount++
                        if(!b.pierce) toRemoveBulletIdx.add(bi)
                        if(curHp<=0) break
                    }
                }
                if(hitCount>0){
                    if(curHp<=0){
                        if(e.isBoss){ ns+=20; bonusChips+=20; bossKilled=true; wave++ }
                        else { ns++; bonusChips+=1 }
                        shake= if(e.isBoss) 12f else 3f
                    } else {
                        aliveEnemies.add(e.copy(hp=curHp))
                    }
                } else {
                    if(!e.isBoss && e.y > ch - 88f){
                        lives--
                        shake=6f
                        if(lives<=0){
                            over=true; earned=score*2
                            if(earned>0){ val ev=earned; scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)+ev } }catch(_:Exception){} } }
                        }
                    } else aliveEnemies.add(e)
                }
            }
            val filteredBullets = aliveBullets.filterIndexed{ i,_ -> i !in toRemoveBulletIdx }
            if(bonusChips>0) scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)+bonusChips } }catch(_:Exception){} }
            if(ns!=score){
                score=ns
                if(!bossKilled && !isBossWave(wave) && score>0 && score%12==0){
                    wave++
                    if(wave%3==0) showShop=true // shop only every 3 waves + boss wave (ponytail: 3-wave interval, lower frequency)
                } else if(bossKilled){ showShop=true }
            }
            enemies=aliveEnemies
            bullets=filteredBullets
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
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.SurfaceAlt).padding(10.dp), contentAlignment=Alignment.Center){ Text("♥".repeat(lives.coerceAtLeast(0))+"♡".repeat((5-lives).coerceAtLeast(0)), color=ArcadeTokens.Danger, fontWeight=FontWeight.Bold, fontSize=14.sp) }
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if(isBossWave(wave)) ArcadeTokens.DangerContainer else ArcadeTokens.PrimaryContainer).padding(horizontal=10.dp, vertical=10.dp)){ Text(if(isBossWave(wave)) "BOSS W$wave" else "W$wave", fontWeight=FontWeight.Black, fontSize=12.sp, color=if(isBossWave(wave)) ArcadeTokens.Danger else ArcadeTokens.PrimaryDark) }
                if(earned>0) Box(Modifier.clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.AccentContainer).padding(10.dp)){ Text("+$earned", fontWeight=FontWeight.Bold, fontSize=12.sp) }
            }
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF0F1220))){
                Canvas(modifier=Modifier.fillMaxSize().pointerInput(Unit){
                    detectDragGestures{ _, drag ->
                        val mul = if(speedBoost) 1.45f else 1f
                        playerX=(playerX + drag.x*mul).coerceIn(28f, cw-28f)
                    }
                }){
                    cw=size.width; ch=size.height
                    val sxOffset = if(shake>0) Random.nextFloat()*shake - shake/2 else 0f
                    val syOffset = if(shake>0) Random.nextFloat()*shake*0.5f else 0f
                    drawRect(Color(0xFF0F1220), size=size)
                    for(i in 0 until 34){ val sx=(i*137)%size.width; val sy=(i*271 + (score*7)%480)%size.height; drawCircle(Color.White.copy(alpha=0.22f), radius=1.7f, center=Offset(sx.toFloat()+sxOffset, sy.toFloat()+syOffset)) }
                    // player
                    val px=playerX+sxOffset; val py=size.height-70f+syOffset
                    drawRoundRect(Color(0xFF00B894), topLeft=Offset(px-26, py-18), size=Size(52f,34f), cornerRadius=CornerRadius(8f,8f))
                    drawRoundRect(Color.White, topLeft=Offset(px-10, py-30), size=Size(20f,18f), cornerRadius=CornerRadius(6f,6f))
                    if(speedBoost){ drawCircle(Color(0xFF00B894).copy(alpha=0.35f), radius=30f, center=Offset(px, py)) }
                    // enemies
                    enemies.forEach{ e->
                        val isBoss=e.isBoss
                        val w=if(isBoss) 96f else e.type.w
                        val h=if(isBoss) 54f else e.type.h
                        val col=if(isBoss) Color(0xFF6C3483) else e.type.col
                        // shadow
                        drawRoundRect(Color.Black.copy(alpha=0.28f), topLeft=Offset(e.x-w/2+3, e.y-h/2+4), size=Size(w,h), cornerRadius=CornerRadius(10f,10f))
                        drawRoundRect(col, topLeft=Offset(e.x-w/2, e.y-h/2), size=Size(w,h), cornerRadius=CornerRadius(10f,10f))
                        if(isBoss){
                            drawRoundRect(Color(0xFF9B59B6), topLeft=Offset(e.x-w/2+6, e.y-h/2+6), size=Size(w-12f, 10f), cornerRadius=CornerRadius(4f,4f))
                            // HP bar above boss
                            val barW=w*1.1f; val barH=7f; val pct=(e.hp.toFloat()/e.maxHp.coerceAtLeast(1)).coerceIn(0f,1f)
                            drawRoundRect(Color.Black.copy(alpha=0.5f), topLeft=Offset(e.x-barW/2, e.y-h/2-16), size=Size(barW, barH), cornerRadius=CornerRadius(4f,4f))
                            drawRoundRect(Color(0xFFE74C3C), topLeft=Offset(e.x-barW/2+1, e.y-h/2-15), size=Size((barW-2)*pct, barH-2), cornerRadius=CornerRadius(3f,3f))
                            drawCircle(Color.White, radius=6f, center=Offset(e.x-14, e.y))
                            drawCircle(Color.White, radius=6f, center=Offset(e.x+14, e.y))
                            drawCircle(Color(0xFFE74C3C), radius=3f, center=Offset(e.x-14, e.y))
                            drawCircle(Color(0xFFE74C3C), radius=3f, center=Offset(e.x+14, e.y))
                        } else {
                            drawCircle(Color.White, radius=5.5f, center=Offset(e.x-8, e.y-2))
                            drawCircle(Color.White, radius=5.5f, center=Offset(e.x+8, e.y-2))
                            // tiny hp indicator
                            if(e.maxHp>1){
                                val pct=e.hp.toFloat()/e.maxHp
                                drawRoundRect(Color.Black.copy(alpha=0.45f), topLeft=Offset(e.x-w/2, e.y-h/2-9), size=Size(w,4f), cornerRadius=CornerRadius(2f,2f))
                                drawRoundRect(Color(0xFF00B894), topLeft=Offset(e.x-w/2+1, e.y-h/2-8), size=Size((w-2)*pct,2f), cornerRadius=CornerRadius(1f,1f))
                            }
                        }
                    }
                    // bullets
                    bullets.forEach{ b->
                        val col=if(b.pierce) Color(0xFF00CEC9) else if(b.homing) Color(0xFF9B59B6) else Color(0xFFFDCB6E)
                        drawRoundRect(col, topLeft=Offset(b.x-4+sxOffset, b.y-12+syOffset), size=Size(8f,16f), cornerRadius=CornerRadius(4f,4f))
                        if(b.pierce) drawCircle(Color.White.copy(alpha=0.9f), radius=2.2f, center=Offset(b.x+sxOffset, b.y+syOffset))
                    }
                    // boss bullets
                    bossBullets.forEach{ bb->
                        drawCircle(Color(0xFFE74C3C), radius=7f, center=Offset(bb.x+sxOffset, bb.y+syOffset))
                        drawCircle(Color(0xFFFF7675), radius=3.5f, center=Offset(bb.x+sxOffset, bb.y+syOffset))
                    }
                }
                if(!over && !showShop){
                    Row(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Button(onClick={ showShop=true }, modifier=Modifier.height(36.dp), contentPadding=PaddingValues(horizontal=10.dp, vertical=0.dp), colors=ButtonDefaults.buttonColors(containerColor=ArcadeTokens.PrimaryDark)){ Text("SHOP", fontSize=12.sp, fontWeight=FontWeight.Black) }
                        if(bombs>0) Button(onClick={ doBomb() }, modifier=Modifier.height(36.dp), contentPadding=PaddingValues(horizontal=12.dp, vertical=0.dp), colors=ButtonDefaults.buttonColors(containerColor=ArcadeTokens.Danger)){
                            Text("BOMB x$bombs", fontSize=12.sp, fontWeight=FontWeight.Black)
                        }
                    }
                }
                if(showShop && !over){
                    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment=Alignment.Center){
                        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(ArcadeTokens.Surface).padding(14.dp).fillMaxWidth(0.96f).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(8.dp), horizontalAlignment=Alignment.CenterHorizontally){
                            Text(if(isBossWave(wave)) "BOSS DEFEATED — SHOP" else "WAVE $wave — UPGRADE SHOP", fontWeight=FontWeight.Black, fontSize=14.sp)
                            Text("Chips $chips • Score $score", style=MaterialTheme.typography.labelLarge, color=ArcadeTokens.TextMuted)
                            UpgradeRow2("Fire Rate", "-60ms (${fireMs}ms)", cost=25, chips=chips, owned="${fireMs}ms", enabled=fireMs>160, onBuy={
                                scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)-25 } }catch(_:Exception){} }; fireMs=(fireMs-60).coerceAtLeast(140)
                            })
                            UpgradeRow2("Double Shot", "2 bullets side-by-side", cost=60, chips=chips, owned=if(doubleShot) "Owned" else "—", enabled=!doubleShot && !tripleShot, onBuy={
                                scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)-60 } }catch(_:Exception){} }; doubleShot=true
                            })
                            UpgradeRow2("Triple Shot", "3 bullets spread", cost=90, chips=chips, owned=if(tripleShot) "Owned" else "—", enabled=!tripleShot, onBuy={
                                scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)-90 } }catch(_:Exception){} }; tripleShot=true; doubleShot=false
                            })
                            UpgradeRow2("Damage +1", "dmg $dmg → ${dmg+1}", cost=40, chips=chips, owned="Lv $dmg", enabled=true, onBuy={
                                scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)-40 } }catch(_:Exception){} }; dmg+=1
                            })
                            UpgradeRow2("Pierce", "bullet through enemies", cost=80, chips=chips, owned=if(pierce) "Owned" else "—", enabled=!pierce, onBuy={
                                scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)-80 } }catch(_:Exception){} }; pierce=true
                            })
                            UpgradeRow2("Shield +1 life", "lives $lives → ${lives+1}", cost=50, chips=chips, owned="$lives ♥", enabled=lives<5, onBuy={
                                scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)-50 } }catch(_:Exception){} }; lives+=1
                            })
                            UpgradeRow2("Speed Boost", "drag 45% faster", cost=30, chips=chips, owned=if(speedBoost) "Owned" else "—", enabled=!speedBoost, onBuy={
                                scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)-30 } }catch(_:Exception){} }; speedBoost=true
                            })
                            UpgradeRow2("Bomb", "clear screen / -12 boss HP", cost=70, chips=chips, owned="x$bombs", enabled=true, onBuy={
                                scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)-70 } }catch(_:Exception){} }; bombs+=1
                            })
                            UpgradeRow2("Homing", "bullets track nearest", cost=100, chips=chips, owned=if(homing) "Owned" else "—", enabled=!homing, onBuy={
                                scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)-100 } }catch(_:Exception){} }; homing=true
                            })
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()){
                                Button(onClick={ showShop=false }, modifier=Modifier.weight(1f).height(44.dp)){ Text("Continue") }
                                OutlinedButton(onClick={ showShop=false }, modifier=Modifier.weight(1f).height(44.dp)){ Text("Skip") }
                            }
                            Text("Boss every 5 waves • HP 20+wave*5 • Boss kill +20 score +20 chips • Per-kill +1 chip", color=ArcadeTokens.TextFaint, fontSize=10.sp)
                        }
                    }
                }
                if(over){
                    Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment=Alignment.Center){
                        Column(horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.clip(RoundedCornerShape(16.dp)).background(ArcadeTokens.Surface).padding(20.dp)){
                            Text("GAME OVER", fontWeight=FontWeight.Black, fontSize=20.sp)
                            Text("Score $score • Wave $wave", fontWeight=FontWeight.Bold)
                            if(earned>0) Text("Earned +$earned chips (score×2) + per-kill chips", color=ArcadeTokens.Accent, fontWeight=FontWeight.Bold, fontSize=11.sp)
                            Button(onClick={ reset() }, modifier=Modifier.height(44.dp)){ Text("Play Again") }
                        }
                    }
                }
                if(!over && !showShop){
                    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment=Alignment.BottomCenter){
                        Text("Drag to move • Auto fire • Shop every 3 waves + Boss W5/10/15 • Tap SHOP anytime", color=Color.White.copy(alpha=0.55f), fontSize=10.sp)
                    }
                }
            }
        }
    }
}
@Composable private fun UpgradeRow2(label:String, desc:String, cost:Int, chips:Int, owned:String, enabled:Boolean, onBuy:()->Unit){
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.BgMuted).padding(10.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.SpaceBetween){
        Column(modifier=Modifier.weight(1f)){ Text(label, fontWeight=FontWeight.Bold, fontSize=13.sp); Text(desc, color=ArcadeTokens.TextMuted, fontSize=11.sp); Text(owned, color=ArcadeTokens.PrimaryDark, fontSize=10.sp, fontWeight=FontWeight.Bold) }
        Button(onClick=onBuy, enabled=enabled && chips>=cost, modifier=Modifier.height(36.dp), contentPadding=PaddingValues(horizontal=10.dp, vertical=0.dp)){
            Text(if(!enabled) "Max" else "$cost c", fontSize=12.sp)
        }
    }
}
