package com.rennvol.miniarcade.games.snakes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import com.rennvol.miniarcade.data.Prefs
import com.rennvol.miniarcade.data.dataStore
import com.rennvol.miniarcade.ui.theme.ArcadeTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private data class Snake(val head:Int, val tail:Int)
private data class Ladder(val bottom:Int, val top:Int)

private fun generateBoard(seed: Long): Pair<List<Snake>, List<Ladder>> {
    val r = Random(seed)
    val used = mutableSetOf<Int>()
    used.addAll(listOf(1, 100))
    val snakes = mutableListOf<Snake>()
    val ladders = mutableListOf<Ladder>()
    // ponytail: pickHead removed — inline loop below
    // snakes 6-8
    val sCount = r.nextInt(6,9)
    repeat(sCount){
        var tries=0
        while(tries<40){
            val h = r.nextInt(22, 99)
            val t = r.nextInt(2, h-10)
            if(h !in used && t !in used && h!=t && snakes.none{ it.head==h || it.tail==t } && ladders.none{ it.bottom==t || it.top==h }){
                snakes.add(Snake(h,t)); used.add(h); used.add(t); break
            }
            tries++
        }
    }
    val lCount = r.nextInt(6,9)
    repeat(lCount){
        var tries=0
        while(tries<40){
            val b = r.nextInt(3, 60)
            val top = r.nextInt(b+12, 98)
            if(b !in used && top !in used && b!=top && snakes.none{ it.head==top || it.tail==b } && ladders.none{ it.bottom==b || it.top==top }){
                // ladder bottom low, top high
                ladders.add(Ladder(b,top)); used.add(b); used.add(t)op; break
            }
            tries++
        }
    }
    return snakes to ladders
}

private fun cellColor(n:Int, snakes:List<Snake>, ladders:List<Ladder>):Color{
    return when{
        snakes.any{it.head==n} -> Color(0xFFFFEBEE)
        snakes.any{it.tail==n} -> Color(0xFFFFCDD2)
        ladders.any{it.bottom==n} -> Color(0xFFE8F5E9)
        ladders.any{it.top==n} -> Color(0xFFC8E6C9)
        n%2==0 -> ArcadeTokens.Surface
        else -> ArcadeTokens.BgMuted
    }
}

@Composable
fun SnakesLadderScreen(onBack:()->Unit){
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var chips by remember{ mutableIntStateOf(1000) }
    var enemyCount by remember{ mutableIntStateOf(2) } // 1..3
    var stage by remember{ mutableIntStateOf(1) }
    var seed by remember{ mutableLongStateOf(Random.nextLong()) }
    var snakes by remember{ mutableStateOf(listOf<Snake>()) }
    var ladders by remember{ mutableStateOf(listOf<Ladder>()) }
    var positions by remember{ mutableStateOf(listOf(0,0,0,0)) } // idx0 you, 1..3 bots
    var turn by remember{ mutableIntStateOf(0) } // 0..enemyCount
    var dice by remember{ mutableIntStateOf(1) }
    var rolling by remember{ mutableStateOf(false) }
    var msg by remember{ mutableStateOf("Pilih musuh 1-3 lalu Roll! Tangga naik, ular turun.") }
    var winner by remember{ mutableStateOf<Int?>(null) }
    var showHelp by remember{ mutableStateOf(false) }
    var playerCount by remember{ derivedStateOf{ enemyCount+1 } }

    LaunchedEffect(Unit){ try{ ctx.dataStore.data.collect{ chips=it[Prefs.ARCADE_CHIPS]?:1000 } }catch(_:Exception){} }
    suspend fun save(c:Int){ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=c } }catch(_:Exception){} }

    fun regen(newStage:Int, newSeed:Long){
        val (s,l)=generateBoard(newSeed)
        snakes=s; ladders=l
        positions=List(4){0}
        turn=0; dice=1; winner=null
        msg="Stage $newStage — papan baru! ${s.size} ular, ${l.size} tangga. Roll!"
    }
    LaunchedEffect(seed, stage){ regen(stage, seed) }

    fun applySnakeLadder(pos:Int): Pair<Int,String>{
        snakes.find{it.head==pos}?.let{ return it.tail to "🐍 Ular ${it.head}→${it.tail}!" }
        ladders.find{it.bottom==pos}?.let{ return it.top to "🪜 Tangga ${it.bottom}→${it.top}!" }
        return pos to ""
    }

    fun checkWin(next:List<Int>){
        for(i in next.indices.take(playerCount)){
            if(next[i]==100 && winner==null){
                winner=i
                if(i==0){
                    val earn = 30 + stage*5
                    chips+=earn; scope.launch{ save(chips) }
                    msg="KAMU MENANG Stage $stage! +$earn chips"
                } else msg="Bot ${i} menang Stage $stage!"
            }
        }
    }

    fun doMove(player:Int, steps:Int){
        if(winner!=null) return
        val cur=positions[player]
        var next = cur + steps
        // need exact 100
        if(cur==0){
            // from start 0, 1..6 -> 1..6
            if(next>100) next=cur
        } else {
            if(next>100) next=cur // stay if overshoot
        }
        var note=""
        if(next!=cur){
            val (after, n2)=applySnakeLadder(next)
            note=n2
            next=after
        }
        val newPos = positions.toMutableList().also{ it[player]=next }
        positions=newPos
        if(note.isNotEmpty()) msg= (if(player==0) "Kamu" else "Bot $player") + " $steps → $next $note"
        else msg= (if(player==0) "Kamu" else "Bot $player") + " roll $steps → $next"
        checkWin(newPos)
        if(winner==null){
            turn=(player+1) % playerCount
        }
    }

    fun roll(){
        if(rolling || winner!=null) return
        if(turn!=0) return // not your turn
        rolling=true
        scope.launch{
            // dice anim
            repeat(7){ dice=Random.nextInt(1,7); delay(60) }
            val v = Random.nextInt(1,7); dice=v
            doMove(0,v)
            rolling=false
            // bots auto
            if(winner==null){
                for(b in 1..enemyCount){
                    if(winner!=null) break
                    if(turn==b){
                        delay(750)
                        val bv = Random.nextInt(1,7)
                        dice=bv
                        delay(250)
                        doMove(b,bv)
                    }
                }
            }
        }
    }


    Surface(color=ArcadeTokens.Bg, modifier=Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(12.dp)){
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick=onBack, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.ArrowBack,"back") }
                Text("ULAR TANGGA", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Black)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp), verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.Surface).padding(horizontal=12.dp, vertical=8.dp)){ Text("Chips $chips", fontWeight=FontWeight.Bold, fontSize=12.sp) }
                    IconButton(onClick={showHelp=true}, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.HelpOutline,"help") }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(10.dp)){
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.PrimaryContainer).padding(10.dp)){
                    Text("Ular Tangga • Stage $stage • Papan acak tiap New Game / Next Stage • 1-3 musuh • Roll 1-6, tepat 100 menang. Tangga naik, ular turun.", style=MaterialTheme.typography.labelMedium)
                }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(when{ winner==0 -> ArcadeTokens.AccentContainer; winner!=null -> ArcadeTokens.DangerContainer; else->ArcadeTokens.Surface }).padding(10.dp), contentAlignment=Alignment.Center){
                    Text(msg, fontWeight=FontWeight.Bold, fontSize=12.sp, textAlign=TextAlign.Center)
                }
                // controls
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.Surface).padding(horizontal=10.dp, vertical=8.dp)){ Text("Musuh: $enemyCount", fontWeight=FontWeight.Bold, fontSize=12.sp) }
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        for(n in 1..3){
                            FilterChip(selected=enemyCount==n, onClick={ if(winner==null && positions.all{it==0}) enemyCount=n else { enemyCount=n; seed=Random.nextLong(); stage=1 } }, label={Text("$n")})
                        }
                    }
                    Text("Stage $stage", fontWeight=FontWeight.Black)
                }
                // board 10x10
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0F3D2C)).padding(8.dp), verticalArrangement=Arrangement.spacedBy(4.dp)){
                    for(row in 9 downTo 0){
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(4.dp)){
                            val leftToRight = row%2==0
                            val nums = if(leftToRight) (row*10+1 .. row*10+10).toList() else (row*10+10 downTo row*10+1).toList()
                            for(n in nums){
                                val bg = cellColor(n, snakes, ladders)
                                val playersHere = positions.mapIndexed{ idx,p-> if(p==n) idx else null }.filterNotNull()
                                val isStart = n==1
                                Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(bg).border(1.dp, Color.White.copy(alpha=0.15f), RoundedCornerShape(8.dp)), contentAlignment=Alignment.Center){
                                    Column(horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center){
                                        Text("$n", fontSize=8.sp, fontWeight=FontWeight.Bold, color=Color(0xFF2D3436), lineHeight=8.sp)
                                        when{
                                            snakes.any{it.head==n} -> Text("🐍", fontSize=9.sp, lineHeight=9.sp)
                                            snakes.any{it.tail==n} -> Text("↓", fontSize=8.sp, color=Color(0xFFC62828), fontWeight=FontWeight.Black)
                                            ladders.any{it.bottom==n} -> Text("🪜", fontSize=9.sp, lineHeight=9.sp)
                                            ladders.any{it.top==n} -> Text("↑", fontSize=8.sp, color=Color(0xFF2E7D32), fontWeight=FontWeight.Black)
                                            isStart -> Text("S", fontSize=8.sp, color=ArcadeTokens.Primary, fontWeight=FontWeight.Black)
                                        }
                                    }
                                    if(playersHere.isNotEmpty()){
                                        Row(Modifier.align(Alignment.BottomCenter).padding(bottom=2.dp), horizontalArrangement=Arrangement.spacedBy(1.dp)){
                                            for(p in playersHere.take(4)){
                                                val col = when(p){0->Color(0xFF0984E3);1->Color(0xFFD63031);2->Color(0xFF00B894);3->Color(0xFFFDCB6E);else->Color.Gray}
                                                val label = if(p==0)"K" else "B$p"
                                                Box(Modifier.size(12.dp).clip(CircleShape).background(col).border(1.dp, Color.White, CircleShape), contentAlignment=Alignment.Center){
                                                    Text(label, fontSize=7.sp, color=Color.White, fontWeight=FontWeight.Black, lineHeight=7.sp)
                                                }
                                            }
                                        }
                                    }
                                    // top marker for snakes/ladders destination hint optional
                                }
                            }
                        }
                    }
                }
                // legend + positions
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    for(i in 0 until playerCount){
                        val col = when(i){0->Color(0xFF0984E3);1->Color(0xFFD63031);2->Color(0xFF00B894);3->Color(0xFFFDCB6E);else->Color.Gray}
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if(turn==i && winner==null) col else ArcadeTokens.Surface).padding(8.dp), contentAlignment=Alignment.Center){
                            Column(horizontalAlignment=Alignment.CenterHorizontally){
                                Text(if(i==0)"Kamu" else "Bot $i", fontWeight=FontWeight.Bold, fontSize=11.sp, color=if(turn==i && winner==null) Color.White else ArcadeTokens.Text)
                                Text("${positions[i]}", fontWeight=FontWeight.Black, fontSize=13.sp, color=if(turn==i && winner==null) Color.White else ArcadeTokens.Text)
                                if(turn==i && winner==null) Text("giliran", fontSize=9.sp, color=Color.White)
                            }
                        }
                    }
                }
                Text("🐍 ${snakes.joinToString(", "){ "${it.head}→${it.tail}" }}", fontSize=10.sp, color=ArcadeTokens.TextMuted)
                Text("🪜 ${ladders.joinToString(", "){ "${it.bottom}→${it.top}" }}", fontSize=10.sp, color=ArcadeTokens.TextMuted)
            }
            Spacer(Modifier.height(8.dp))
            // dice + roll
            Row(Modifier.fillMaxWidth().navigationBarsPadding(), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.Surface).border(2.dp, ArcadeTokens.Primary, RoundedCornerShape(12.dp)), contentAlignment=Alignment.Center){
                    Text("$dice", fontWeight=FontWeight.Black, fontSize=22.sp)
                }
                if(winner==null){
                    Button(onClick={roll()}, enabled=!rolling && turn==0, modifier=Modifier.weight(1f).height(52.dp)){
                        Icon(Icons.Filled.Casino, null); Spacer(Modifier.width(8.dp)); Text(if(turn==0) "ROLL" else "Giliran Bot $turn")
                    }
                } else {
                    Button(onClick={
                        if(winner==0){ stage+=1; seed=Random.nextLong() } else { seed=Random.nextLong(); stage=1 }
                    }, modifier=Modifier.weight(1f).height(52.dp), colors=ButtonDefaults.buttonColors(containerColor=if(winner==0) ArcadeTokens.Accent else ArcadeTokens.Danger)){
                        Text(if(winner==0) "Next Stage ($stage→${stage+1})" else "New Game")
                    }
                }
                OutlinedButton(onClick={ seed=Random.nextLong(); /* keep stage */ }, modifier=Modifier.height(52.dp)){ Text("Acak Papan") }
            }
            if(chips<=0){
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.DangerContainer).padding(10.dp), contentAlignment=Alignment.Center){
                    Text("Chips habis — menang stage dapat +30+stage*5", color=ArcadeTokens.Danger, fontWeight=FontWeight.Bold, fontSize=11.sp)
                }
            }
        }
    }
    if(showHelp){
        AlertDialog(onDismissRequest={showHelp=false}, title={Text("Ular Tangga")}, text={
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                Text("Roll 1-6, maju tepat 100 menang. Kelebihan tetap diam.", style=MaterialTheme.typography.bodyMedium)
                Text("Tiap New Game / Next Stage papan acak: 6-8 ular + 6-8 tangga beda posisi. Pilih 1-3 musuh sebelum mulai.", fontSize=12.sp, color=ArcadeTokens.TextMuted)
                Text("Giliran bergantian, bot auto roll 520ms setelah kamu.", fontSize=11.sp, color=ArcadeTokens.TextMuted)
            }
        }, confirmButton={ TextButton(onClick={showHelp=false}){Text("Got it")}} )
    }
}
