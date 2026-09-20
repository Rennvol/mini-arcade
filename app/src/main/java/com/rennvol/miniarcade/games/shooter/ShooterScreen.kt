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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private enum class EType(val hp:Int, val speedMul:Float, val w:Float, val h:Float, val col:Color){
    SMALL(1, 1.0f, 40f, 26f, Color(0xFFFF7675)),
    MEDIUM(2, 0.85f, 52f, 32f, Color(0xFFFDCB6E)),
    TANK(5, 0.52f, 64f, 40f, Color(0xFF636E72))
}
private data class Enemy(var x:Float, var y:Float, var hp:Int, var maxHp:Int, var type:EType, var isBoss:Boolean=false)
private data class Bullet(var x:Float, var y:Float, var vx:Float=0f, var dmg:Int, var pierce:Boolean=false, var homing:Boolean=false, var big:Boolean=false)
private data class BossBullet(var x:Float, var y:Float)
private data class Wingman(var xOff:Float)

// 20 weapon/support items — each is a shop entry (ponytail: flat list, no class hierarchy)
private data class ShopItem(val id:Int, val name:String, val desc:String, val cost:Int)
private val SHOP_ITEMS = listOf(
    ShopItem(0,"Rapid I","Fire -70ms",25),
    ShopItem(1,"Rapid II","Fire -70ms",35),
    ShopItem(2,"Rapid III Vulcan","Fire -80ms",55),
    ShopItem(3,"Double Shot","2 bullets",60),
    ShopItem(4,"Triple Shot","3 spread",90),
    ShopItem(5,"Shotgun 5","5 spread 18°",110),
    ShopItem(6,"Hellfire 7","7 spread",160),
    ShopItem(7,"Pierce","Through enemies",80),
    ShopItem(8,"Heavy Pierce +","Pierce + dmg",120),
    ShopItem(9,"Homing","Track nearest",100),
    ShopItem(10,"Swarm Homing+","Homing + 2 extra",150),
    ShopItem(11,"Damage +1","dmg+1",40),
    ShopItem(12,"Plasma +2","dmg+2 big bullet",90),
    ShopItem(13,"Ion +3","dmg+3 laser",140),
    ShopItem(14,"Speed Boost","Drag +45%",30),
    ShopItem(15,"Shield +1 life","lives+1 max5",50),
    ShopItem(16,"Wingman α","1 drone side fire",130),
    ShopItem(17,"Fleet β","2 drones (need α)",180),
    ShopItem(18,"Bomb","Clear / -12 boss HP",70),
    ShopItem(19,"Nuke","Bomb + pierce burst",200),
)

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
    // weapon state
    var fireMs by remember{ mutableIntStateOf(420) }
    var doubleShot by remember{ mutableStateOf(false) }
    var tripleShot by remember{ mutableStateOf(false) }
    var shotgun5 by remember{ mutableStateOf(false) }
    var hellfire7 by remember{ mutableStateOf(false) }
    var dmg by remember{ mutableIntStateOf(1) }
    var pierce by remember{ mutableStateOf(false) }
    var heavyPierce by remember{ mutableStateOf(false) }
    var homing by remember{ mutableStateOf(false) }
    var swarm by remember{ mutableStateOf(false) }
    var speedBoost by remember{ mutableStateOf(false) }
    var wingmen by remember{ mutableIntStateOf(0) } // 0..2
    var bombs by remember{ mutableIntStateOf(0) }
    var bigBullet by remember{ mutableStateOf(false) }
    var bought by remember{ mutableStateOf(setOf<Int>()) }

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
        fireMs=420; doubleShot=false; tripleShot=false; shotgun5=false; hellfire7=false
        dmg=1; pierce=false; heavyPierce=false; homing=false; swarm=false; speedBoost=false; wingmen=0; bombs=0; bigBullet=false; bought=setOf()
        showShop=false; enemies=listOf(); bullets=listOf(); bossBullets=listOf(); playerX=cw/2
    }
    fun doBomb(){
        if(bombs<=0) return
        bombs--
        val isBoss= enemies.any{ it.isBoss }
        if(isBoss){
            enemies=enemies.map{ if(it.isBoss) it.copy(hp= it.hp - 12) else it }.filter{ it.hp>0 }
            if(enemies.none{ it.isBoss }){ score+=20; scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)+20 } }catch(_:Exception){} } }
        } else {
            val cleared=enemies.size
            if(cleared>0){ score+=cleared; scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)+cleared } }catch(_:Exception){} } }
            enemies=listOf()
        }
        shake=10f
    }
    fun buy(id:Int, cost:Int, onOk:()->Unit){
        if(id in bought || chips < cost) return
        scope.launch{ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=(it[Prefs.ARCADE_CHIPS]?:1000)-cost } }catch(_:Exception){} }
        bought = bought + id
        onOk()
    }

    // auto shoot — pattern depends on weapons
    LaunchedEffect(over, fireMs, doubleShot, tripleShot, shotgun5, hellfire7, dmg, pierce, homing, swarm, wingmen, bigBullet, showShop){
        while(!over){
            delay(fireMs.toLong())
            if(showShop) continue
            val py=ch-110f
            val baseDmg = dmg + (if(heavyPierce)1 else 0)
            val isPierce = pierce || heavyPierce
            val isHoming = homing || swarm
            val list = mutableListOf<Bullet>()
            fun addBullet(x:Float, vx:Float=0f){
                list.add(Bullet(x, py, vx, baseDmg, isPierce, isHoming, bigBullet))
            }
            when {
                hellfire7 -> {
                    val spread = listOf(-18f,-12f,-6f,0f,6f,12f,18f)
                    spread.forEach{ deg ->
                        val rad = deg * 3.14159f/180f
                        addBullet(playerX, sin(rad)*5f)
                    }
                    // wingmen extra
                    repeat(wingmen){ idx ->
                        val wx = playerX + if(idx==0) -42f else 42f
                        spread.take(3).forEach{ deg ->
                            val rad = deg * 3.14159f/180f
                            list.add(Bullet(wx, py-6f, sin(rad)*4f, baseDmg, isPierce, isHoming, bigBullet))
                        }
                    }
                }
                shotgun5 -> {
                    val spread = listOf(-12f,-6f,0f,6f,12f)
                    spread.forEach{ deg ->
                        val rad = deg * 3.14159f/180f
                        addBullet(playerX, sin(rad)*5f)
                    }
                    repeat(wingmen){ idx ->
                        val wx = playerX + if(idx==0) -42f else 42f
                        list.add(Bullet(wx, py-6f, 0f, baseDmg, isPierce, isHoming, bigBullet))
                    }
                }
                tripleShot -> {
                    addBullet(playerX-18f); addBullet(playerX); addBullet(playerX+18f)
                    repeat(wingmen){ idx ->
                        val wx = playerX + if(idx==0) -42f else 42f
                        list.add(Bullet(wx, py-6f, 0f, baseDmg, isPierce, isHoming, bigBullet))
                    }
                }
                doubleShot -> {
                    addBullet(playerX-14f); addBullet(playerX+14f)
                    repeat(wingmen){ idx ->
                        val wx = playerX + if(idx==0) -42f else 42f
                        list.add(Bullet(wx, py-6f, 0f, baseDmg, isPierce, isHoming, bigBullet))
                    }
                }
                else -> {
                    addBullet(playerX)
                    repeat(wingmen){ idx ->
                        val wx = playerX + if(idx==0) -42f else 42f
                        list.add(Bullet(wx, py-6f, 0f, baseDmg, isPierce, isHoming, bigBullet))
                    }
                }
            }
            // swarm extra homing pair
            if(swarm){
                list.add(Bullet(playerX-10f, py, -1.5f, baseDmg, isPierce, true, bigBullet))
                list.add(Bullet(playerX+10f, py, 1.5f, baseDmg, isPierce, true, bigBullet))
            }
            bullets=bullets+list
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
            if(isBossWave(wave) && enemies.none{ it.isBoss } && !bossSpawnedForWave){
                val hp=bossHp(wave)
                enemies=enemies+Enemy(x=cw/2, y=80f, hp=hp, maxHp=hp, type=EType.TANK, isBoss=true)
                bossSpawnedForWave=true
            }
            val bossAlive=enemies.any{ it.isBoss }
            if(!bossAlive){
                if(bossSpawnedForWave) bossSpawnedForWave=false
                spawnAcc++
                // scaling: wave1 ~ 1 spawn / 1.6s, wave10 ~ 1 / 0.6s  (was 0.5s at wave1 too hard)
                val threshold = (96 - wave*5).coerceAtLeast(28)
                if(spawnAcc > threshold){
                    spawnAcc=0
                    val t = when(Random.nextInt(100)){
                        in 0..55 -> EType.SMALL
                        in 56..85 -> EType.MEDIUM
                        else -> EType.TANK
                    }
                    val hp = t.hp + wave/6
                    enemies=enemies+Enemy(x=Random.nextFloat()*(cw-70)+35, y=-40f, hp=hp, maxHp=hp, type=t)
                }
            } else {
                enemies=enemies.map{ e->
                    if(e.isBoss) e.copy(x= e.x + sin(System.currentTimeMillis()/520.0).toFloat()*1.6f) else e
                }
            }
            // gentle scaling: 1.4 at w1 -> 4.2 at w10 (was 3.2+0.45 -> death at w1)
            val baseSpeed = 1.35f + wave*0.30f
            val nextEnemies = enemies.map{ e->
                if(e.isBoss) e else e.copy(y=e.y + baseSpeed*e.type.speedMul)
            }.toMutableList()

            val nextBullets = mutableListOf<Bullet>()
            for(b in bullets){
                var nx=b.x + b.vx; var ny=b.y - (if(speedBoost) 16f else 14f)
                if(b.homing && enemies.isNotEmpty()){
                    val target=enemies.minByOrNull{ abs(it.x - b.x) + abs(it.y - b.y) }
                    if(target!=null){
                        val dx=target.x - b.x
                        nx += (dx*0.08f).coerceIn(-3.5f,3.5f)
                    }
                } else if(b.vx!=0f){
                    // spread keeps vx
                }
                if(ny > -30 && nx > -40 && nx < cw+40) nextBullets.add(b.copy(x=nx, y=ny))
            }
            val nextBossBullets = bossBullets.map{ it.copy(y=it.y+6.5f) }.filter{ it.y < ch+20 }

            var hitPlayer=false
            for(bb in nextBossBullets){
                if(abs(bb.x - playerX) < 28 && abs(bb.y - (ch-70f)) < 28){ hitPlayer=true; break }
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

            var ns=score
            var bonusChips=0
            var bossKilled=false
            val aliveEnemies = mutableListOf<Enemy>()
            val aliveBullets = nextBullets.toMutableList()
            val toRemoveBulletIdx = mutableSetOf<Int>()
            for(e in nextEnemies){
                var curHp=e.hp
                var hitCount=0
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
                if(!bossKilled && !isBossWave(wave) && score>0 && score%14==0){
                    wave++
                    if(wave%3==0) showShop=true
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
                    // player — visual evolves with upgrades (ponytail: conditional draws, not sprites)
                    val px=playerX+sxOffset; val py=size.height-70f+syOffset
                    // wingmen drones
                    repeat(wingmen){ idx ->
                        val wx = px + if(idx==0) -52f else 52f
                        val wy = py+6f
                        drawRoundRect(Color(0xFF74B9FF), topLeft=Offset(wx-14, wy-10), size=Size(28f,18f), cornerRadius=CornerRadius(6f,6f))
                        drawCircle(Color.White, radius=4f, center=Offset(wx, wy-2))
                        drawRoundRect(Color(0xFF0984E3), topLeft=Offset(wx-10, wy+8), size=Size(20f,4f), cornerRadius=CornerRadius(2f,2f))
                    }
                    // engine glow if speed
                    if(speedBoost) drawCircle(Color(0xFF00B894).copy(alpha=0.28f), radius=34f, center=Offset(px, py+10))
                    // body by tier: more weapons = bigger wings + extra fins
                    val hasWings = doubleShot || tripleShot || shotgun5 || hellfire7
                    val hasHeavy = heavyPierce || bigBullet || hellfire7
                    // shadow
                    drawRoundRect(Color.Black.copy(alpha=0.32f), topLeft=Offset(px-27, py-15+4), size=Size(54f,34f), cornerRadius=CornerRadius(10f,10f))
                    // wings
                    if(hasWings){
                        drawRoundRect(Color(0xFF2D3436), topLeft=Offset(px-38, py-8), size=Size(18f,16f), cornerRadius=CornerRadius(4f,4f))
                        drawRoundRect(Color(0xFF2D3436), topLeft=Offset(px+20, py-8), size=Size(18f,16f), cornerRadius=CornerRadius(4f,4f))
                    }
                    // main body
                    val bodyCol = when {
                        hellfire7 -> Color(0xFF6C3483)
                        shotgun5 -> Color(0xFFE67E22)
                        tripleShot -> Color(0xFF2980B9)
                        doubleShot -> Color(0xFF00B894)
                        else -> Color(0xFF00B894)
                    }
                    drawRoundRect(bodyCol, topLeft=Offset(px-26, py-18), size=Size(52f,34f), cornerRadius=CornerRadius(8f,8f))
                    if(hasHeavy){
                        drawRoundRect(Color(0xFFFDCB6E), topLeft=Offset(px-20, py-14), size=Size(40f,6f), cornerRadius=CornerRadius(3f,3f))
                    }
                    // cockpit
                    drawRoundRect(Color.White, topLeft=Offset(px-10, py-30), size=Size(20f,18f), cornerRadius=CornerRadius(6f,6f))
                    // pierce glow
                    if(pierce||heavyPierce) drawCircle(Color(0xFF00CEC9).copy(alpha=0.35f), radius=22f, center=Offset(px, py-6))
                    if(homing||swarm) drawCircle(Color(0xFF9B59B6).copy(alpha=0.28f), radius=26f, center=Offset(px, py-6))
                    // nose
                    drawCircle(bodyCol, radius=7f, center=Offset(px, py-24))
                    // enemies
                    enemies.forEach{ e->
                        val isBoss=e.isBoss
                        val w=if(isBoss) 96f else e.type.w
                        val h=if(isBoss) 54f else e.type.h
                        val col=if(isBoss) Color(0xFF6C3483) else e.type.col
                        drawRoundRect(Color.Black.copy(alpha=0.28f), topLeft=Offset(e.x-w/2+3, e.y-h/2+4), size=Size(w,h), cornerRadius=CornerRadius(10f,10f))
                        drawRoundRect(col, topLeft=Offset(e.x-w/2, e.y-h/2), size=Size(w,h), cornerRadius=CornerRadius(10f,10f))
                        if(isBoss){
                            drawRoundRect(Color(0xFF9B59B6), topLeft=Offset(e.x-w/2+6, e.y-h/2+6), size=Size(w-12f, 10f), cornerRadius=CornerRadius(4f,4f))
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
                            if(e.maxHp>1){
                                val pct=e.hp.toFloat()/e.maxHp
                                drawRoundRect(Color.Black.copy(alpha=0.45f), topLeft=Offset(e.x-w/2, e.y-h/2-9), size=Size(w,4f), cornerRadius=CornerRadius(2f,2f))
                                drawRoundRect(Color(0xFF00B894), topLeft=Offset(e.x-w/2+1, e.y-h/2-8), size=Size((w-2)*pct,2f), cornerRadius=CornerRadius(1f,1f))
                            }
                        }
                    }
                    // bullets
                    bullets.forEach{ b->
                        val col=when{
                            b.big -> Color(0xFFFFE66D)
                            b.pierce && b.homing -> Color(0xFFE84393)
                            b.pierce -> Color(0xFF00CEC9)
                            b.homing -> Color(0xFF9B59B6)
                            else -> Color(0xFFFDCB6E)
                        }
                        val bh = if(b.big) 22f else 16f
                        val bw = if(b.big) 10f else 8f
                        drawRoundRect(col, topLeft=Offset(b.x-bw/2+sxOffset, b.y-bh+syOffset), size=Size(bw,bh), cornerRadius=CornerRadius(4f,4f))
                        if(b.pierce) drawCircle(Color.White.copy(alpha=0.9f), radius=2.2f, center=Offset(b.x+sxOffset, b.y-6+syOffset))
                    }
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
                            Text(if(isBossWave(wave)) "BOSS DEFEATED — SHOP" else "WAVE $wave — ARSENAL (20)", fontWeight=FontWeight.Black, fontSize=13.sp)
                            Text("Chips $chips • Score $score • Tap buy, ship evolves!", style=MaterialTheme.typography.labelSmall, color=ArcadeTokens.TextMuted)
                            // grouped display
                            for(item in SHOP_ITEMS){
                                val owned = item.id in bought
                                // fleet needs wingman
                                val needWingman = item.id==17 && 16 !in bought
                                val enabled = !owned && !needWingman
                                // extra gate: hellfire needs shotgun, swarm needs homing, heavyPierce needs pierce, fleet needs alpha
                                val gated = when(item.id){
                                    6 -> 5 !in bought
                                    8 -> 7 !in bought
                                    10 -> 9 !in bought
                                    17 -> 16 !in bought
                                    else -> false
                                }
                                val label = when(item.id){
                                    16 -> if(wingmen>=1) "Owned" else "—"
                                    17 -> if(wingmen>=2) "Owned" else "—"
                                    else -> if(owned) "Owned" else "—"
                                }
                                val desc = if(gated) "Need prev tier" else item.desc
                                UpgradeRow2(item.name, desc, item.cost, chips, label, enabled && !gated, onBuy={
                                    buy(item.id, item.cost){
                                        when(item.id){
                                            0 -> fireMs=(fireMs-70).coerceAtLeast(140)
                                            1 -> fireMs=(fireMs-70).coerceAtLeast(140)
                                            2 -> fireMs=(fireMs-80).coerceAtLeast(120)
                                            3 -> doubleShot=true
                                            4 -> { tripleShot=true; doubleShot=false }
                                            5 -> { shotgun5=true; tripleShot=false; doubleShot=false }
                                            6 -> { hellfire7=true; shotgun5=false }
                                            7 -> pierce=true
                                            8 -> { pierce=true; heavyPierce=true }
                                            9 -> homing=true
                                            10 -> { homing=true; swarm=true }
                                            11 -> dmg+=1
                                            12 -> { dmg+=2; bigBullet=true }
                                            13 -> { dmg+=3; bigBullet=true; pierce=true }
                                            14 -> speedBoost=true
                                            15 -> if(lives<5) lives+=1
                                            16 -> if(wingmen<1) wingmen=1
                                            17 -> if(wingmen<2) wingmen=2
                                            18 -> bombs+=1
                                            19 -> { bombs+=1; pierce=true; homing=true; bigBullet=true }
                                        }
                                    }
                                })
                            }
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()){
                                Button(onClick={ showShop=false }, modifier=Modifier.weight(1f).height(44.dp)){ Text("Continue") }
                                OutlinedButton(onClick={ showShop=false }, modifier=Modifier.weight(1f).height(44.dp)){ Text("Skip") }
                            }
                            Text("Wave scales 1.35+0.30/wave • Boss every 5 • Wingmen fire with you • Each tier changes ship", color=ArcadeTokens.TextFaint, fontSize=9.sp)
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
                        Text("Drag to move • Auto fire • Shop every 3 waves + Boss W5/10/15 • 20 weapons • Wingmen evolve ship", color=Color.White.copy(alpha=0.55f), fontSize=9.sp)
                    }
                }
            }
        }
    }
}
@Composable private fun UpgradeRow2(label:String, desc:String, cost:Int, chips:Int, owned:String, enabled:Boolean, onBuy:()->Unit){
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.BgMuted).padding(10.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.SpaceBetween){
        Column(modifier=Modifier.weight(1f)){ Text(label, fontWeight=FontWeight.Bold, fontSize=12.sp); Text(desc, color=ArcadeTokens.TextMuted, fontSize=11.sp); Text(owned, color=ArcadeTokens.PrimaryDark, fontSize=10.sp, fontWeight=FontWeight.Bold) }
        Button(onClick=onBuy, enabled=enabled && chips>=cost, modifier=Modifier.height(36.dp), contentPadding=PaddingValues(horizontal=10.dp, vertical=0.dp)){
            Text(if(!enabled) "Max" else "$cost c", fontSize=12.sp)
        }
    }
}
