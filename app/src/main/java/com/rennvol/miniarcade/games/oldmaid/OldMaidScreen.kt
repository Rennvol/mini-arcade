package com.rennvol.miniarcade.games.oldmaid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

private enum class OSuit(val sym:String,val isRed:Boolean){ Diamond("♦",true), Heart("♥",true), Club("♣",false), Spade("♠",false) }
private data class OCard(val suit:OSuit?=null,val rank:Int=0,val isJoker:Boolean=false){
    fun label():String = if(isJoker) "JOKER" else { val r=when(rank){14->"A";13->"K";12->"Q";11->"J"; else->"$rank"}; r + (suit?.sym?:"") }
    val col:Color get()= if(isJoker) Color(0xFF6C3483) else if(suit?.isRed==true) ArcadeTokens.Danger else Color.Black
    val colorGroup:Int get()= if(isJoker) 2 else if(suit?.isRed==true) 0 else 1
}
private fun newDeck():MutableList<OCard>{ val d=mutableListOf<OCard>(); for(s in OSuit.values()) for(r in 2..14) d.add(OCard(s,r,false)); d.add(OCard(isJoker=true)); d.shuffle(); return d }
private fun isPair(a:OCard,b:OCard):Boolean{
    if(a.isJoker||b.isJoker) return false
    return a.rank==b.rank && a.colorGroup==b.colorGroup
}
private fun removeAllPairs(hand:MutableList<OCard>):List<Pair<OCard,OCard>>{
    val removed=mutableListOf<Pair<OCard,OCard>>()
    var found=true
    while(found){
        found=false
        loop@ for(i in hand.indices) for(j in i+1 until hand.size){
            if(isPair(hand[i], hand[j])){
                removed.add(hand[i] to hand[j])
                // remove j first
                hand.removeAt(j); hand.removeAt(i); found=true; break@loop
            }
        }
    }
    return removed
}

@Composable
fun OldMaidScreen(onBack:()->Unit){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var chips by remember{ mutableIntStateOf(1000) }
    var hands by remember{ mutableStateOf(listOf<MutableList<OCard>>()) }
    var turn by remember{ mutableIntStateOf(0) }
    var msg by remember{ mutableStateOf("Tap Deal — Old Maid Joker vs 3 bots") }
    var phase by remember{ mutableStateOf("idle") } // idle play win
    var lastDiscard by remember{ mutableStateOf<Pair<OCard,OCard>?>(null) }
    var discardCount by remember{ mutableIntStateOf(0) }
    var botThinking by remember{ mutableStateOf(false) }
    var showHelp by remember{ mutableStateOf(false) }
    var winner by remember{ mutableStateOf<Int?>(null) }
    var jokerHolder by remember{ mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit){ try{ ctx.dataStore.data.collect{ chips=it[Prefs.ARCADE_CHIPS]?:1000 } }catch(_:Exception){} }
    suspend fun save(c:Int){ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=c } }catch(_:Exception){} }

    fun aliveIndices():List<Int> = hands.indices.filter{ hands[it].isNotEmpty() }
    fun nextAlive(from:Int):Int?{
        if(hands.isEmpty()) return null
        for(k in 1..4){ val n=(from+k)%4; if(hands[n].isNotEmpty()) return n }
        return null
    }
    fun totalCards():Int = hands.sumOf{ it.size }
    fun findJoker():Int?{ for(i in hands.indices) if(hands[i].any{it.isJoker}) return i; return null }

    fun checkEnd():Boolean{
        val total=totalCards()
        val alive=aliveIndices()
        // win if only joker left
        if(total==1){
            val h=findJoker()
            phase="win"; jokerHolder=h; winner=alive.firstOrNull{ it!=h }
            if(h==0){ msg="Kamu pegang JOKER terakhir — KALAH 😭"; }
            else { val win=60; chips+=win; scope.launch{ save(chips) }; msg="Bot $h pegang JOKER — Kamu MENANG +$win! 🎉" }
            return true
        }
        if(total==0){ phase="win"; msg="Habis semua — seri?"; return true }
        // also if only one alive holds cards (joker holder) and others 0
        if(alive.size==1){
            val h=alive[0]; phase="win"; jokerHolder=h
            if(h==0){ msg="Kamu pegang JOKER terakhir — KALAH 😭"; }
            else { val win=60; chips+=win; scope.launch{ save(chips) }; msg="Bot $h pegang JOKER — Kamu MENANG +$win! 🎉" }
            return true
        }
        return false
    }

    fun botLoop(){
        scope.launch{
            while(phase=="play" && turn!=0){
                botThinking=true; delay(850)
                if(hands[turn].isEmpty()){ turn=nextAlive(turn)?:0; if(turn==0) msg+=" — giliran Kamu"; continue }
                val victim=nextAlive(turn) ?: break
                if(hands[victim].isEmpty()){ turn=nextAlive(turn)?:0; continue }
                // pick random card index
                val pickIdx = kotlin.random.Random.nextInt(hands[victim].size)
                val card = hands[victim].removeAt(pickIdx)
                // check pair in picker hand
                var paired=false
                var pairIdx:Int? = null
                for(i in hands[turn].indices) if(isPair(hands[turn][i], card)){ pairIdx=i; paired=true; break }
                if(paired){
                    val mate=hands[turn].removeAt(pairIdx!!)
                    lastDiscard= mate to card
                    discardCount+=2
                    msg="Bot $turn ambil ${card.label()} dari Bot $victim → buang pair ${mate.label()}+${card.label()} ✓"
                    if(hands[turn].isEmpty()) msg+=" — Bot $turn habis!"
                    jokerHolder=findJoker()
                    if(checkEnd()){ botThinking=false; break }
                    // next turn: picker stays? In old maid, next picker is next alive after current picker
                    turn=nextAlive(turn) ?: 0
                    if(turn==0 && phase=="play") msg+=" — giliran Kamu ambil dari Bot ${nextAlive(0)}"
                } else {
                    hands[turn].add(card)
                    // shuffle a bit
                    hands[turn].shuffle()
                    jokerHolder=findJoker()
                    msg="Bot $turn ambil kartu dari Bot $victim — tidak pair, sisa ${hands[turn].size}"
                    if(checkEnd()){ botThinking=false; break }
                    turn=nextAlive(turn) ?: 0
                    if(turn==0 && phase=="play") msg+=" — giliran Kamu"
                }
            }
            botThinking=false
        }
    }

    fun deal(){
        val d=newDeck()
        val h = listOf(mutableListOf<OCard>(), mutableListOf(), mutableListOf(), mutableListOf<OCard>())
        var idx=0; while(d.isNotEmpty()){ h[idx%4].add(d.removeAt(0)); idx++ }
        var disc=0
        for(p in 0..3){
            val rem=removeAllPairs(h[p])
            disc+=rem.size*2
            if(rem.isNotEmpty()) lastDiscard=rem.last()
        }
        hands=h; discardCount=disc; turn=0; phase="play"; botThinking=false; winner=null; jokerHolder=findJoker()
        msg="Buang pair warna sama — sisa ${h[0].size} kartu. Giliran Kamu ambil dari Bot ${nextAlive(0)?:1}"
        // if you start with 0 (rare) advance?
        if(h[0].isEmpty()){
            msg="Kamu langsung habis! Menang? Tunggu bots..."
            scope.launch{ delay(600); botLoop() }
        }
    }

    fun playerPick(victim:Int, cardIdx:Int){
        if(phase!="play"||turn!=0||botThinking) return
        if(victim !in hands.indices || hands[victim].isEmpty()) return
        if(cardIdx !in hands[victim].indices) return
        val card=hands[victim].removeAt(cardIdx)
        var paired=false
        var pairIdx:Int? = null
        for(i in hands[0].indices) if(isPair(hands[0][i], card)){ pairIdx=i; paired=true; break }
        if(paired){
            val mate=hands[0].removeAt(pairIdx!!)
            lastDiscard=mate to card
            discardCount+=2
            msg="Kamu ambil ${card.label()} dari Bot $victim → buang pair ${mate.label()}+${card.label()} ✓"
            jokerHolder=findJoker()
            if(checkEnd()) return
            turn=nextAlive(0) ?: 0
            if(turn!=0) scope.launch{ delay(400); botLoop() }
            else msg+=" — giliran Kamu lagi"
        } else {
            hands[0].add(card)
            msg="Kamu ambil ${card.label()} dari Bot $victim — tidak pair"
            jokerHolder=findJoker()
            if(checkEnd()) return
            turn=nextAlive(0) ?: 0
            if(turn!=0) scope.launch{ delay(400); botLoop() }
        }
    }

    Surface(color=ArcadeTokens.Bg, modifier=Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(12.dp)){
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick=onBack, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.ArrowBack,"back") }
                Text("OLD MAID", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Black)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp), verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.Surface).padding(horizontal=12.dp, vertical=8.dp)){ Text("Chips $chips", fontWeight=FontWeight.Bold) }
                    IconButton(onClick={showHelp=true}, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.HelpOutline,"help") }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(10.dp)){
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.PrimaryContainer).padding(10.dp)){
                    Text("Ambil 1 kartu dari lawan. Buang pair angka SAMA + WARNA SAMA (♥♦ merah, ♣♠ hitam). Sisa JOKER = kalah.", style=MaterialTheme.typography.labelMedium)
                }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(
                    when{ msg.contains("MENANG") -> ArcadeTokens.AccentContainer
                        msg.contains("KALAH") -> ArcadeTokens.DangerContainer
                        msg.contains("pair") -> ArcadeTokens.SecondaryContainer
                        else -> ArcadeTokens.Surface }
                ).padding(10.dp), contentAlignment=Alignment.Center){
                    Text(msg, fontWeight=FontWeight.Bold, fontSize=12.sp, color=ArcadeTokens.Text)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.Surface).padding(horizontal=10.dp, vertical=6.dp)){ Text("Buang: $discardCount  •  Sisa: ${totalCards()}", fontWeight=FontWeight.Bold, fontSize=11.sp) }
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if(phase=="win") ArcadeTokens.Accent else if(turn==0) ArcadeTokens.Primary else ArcadeTokens.BgMuted).padding(horizontal=10.dp, vertical=6.dp)){ Text(if(phase=="win") "SELESAI" else if(turn==0) "▶ Giliran Kamu" else "⏳ Bot $turn", color=if(turn==0||phase=="win") Color.White else ArcadeTokens.Text, fontWeight=FontWeight.Bold, fontSize=11.sp) }
                }
                // bots
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    for(i in 1..3){
                        val isVictim = phase=="play" && turn==0 && nextAlive(0)==i
                        val isTurn = turn==i && phase=="play"
                        val cnt=hands.getOrNull(i)?.size ?: 0
                        val hasJoker=hands.getOrNull(i)?.any{it.isJoker}==true
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.Surface).border(if(isVictim) 2.dp else if(isTurn) 2.dp else 1.dp, if(isVictim) ArcadeTokens.Accent else if(isTurn) ArcadeTokens.Primary else ArcadeTokens.BgMuted, RoundedCornerShape(12.dp)).padding(8.dp), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Text("Bot $i ${if(isTurn)"▶" else if(isVictim)"◀ target" else ""}", fontWeight=FontWeight.Bold, fontSize=11.sp, color=if(isVictim) ArcadeTokens.Accent else if(isTurn) ArcadeTokens.PrimaryDark else ArcadeTokens.Text)
                            Text("$cnt kartu", fontSize=11.sp, color=ArcadeTokens.TextMuted)
                            if(phase=="win" && hasJoker) Text("JOKER 😈", fontSize=10.sp, fontWeight=FontWeight.Black, color=ArcadeTokens.Danger)
                            if(cnt==0) Text("HABIS ✓", fontSize=10.sp, fontWeight=FontWeight.Bold, color=ArcadeTokens.PrimaryDark)
                            else Row(horizontalArrangement=Arrangement.spacedBy(2.dp)){
                                repeat(minOf(cnt,7)){ Box(Modifier.size(width=14.dp,height=20.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF2D3436))) }
                                if(cnt>7) Text("+${cnt-7}", fontSize=9.sp, color=ArcadeTokens.TextFaint)
                            }
                        }
                    }
                }
                // center discard
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF2C2C2C)).padding(12.dp), contentAlignment=Alignment.Center){
                    Column(horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Text("BUANGAN", color=Color.White.copy(alpha=0.7f), fontSize=10.sp, fontWeight=FontWeight.Bold)
                        if(lastDiscard!=null){
                            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                for(c in listOf(lastDiscard!!.first, lastDiscard!!.second)){
                                    Box(Modifier.size(width=46.dp,height=62.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, ArcadeTokens.Primary, RoundedCornerShape(8.dp)), contentAlignment=Alignment.Center){
                                        Text(c.label(), color=c.col, fontWeight=FontWeight.Black, fontSize=11.sp)
                                    }
                                }
                            }
                            Text("${lastDiscard!!.first.label()} + ${lastDiscard!!.second.label()}  buang", color=Color.White.copy(alpha=0.8f), fontSize=10.sp)
                        } else {
                            Text("— belum ada pair —", color=Color.White.copy(alpha=0.5f), fontSize=11.sp)
                        }
                        if(phase=="win" && jokerHolder!=null) Text("Joker di ${if(jokerHolder==0) "Kamu" else "Bot $jokerHolder"}", color=ArcadeTokens.Accent, fontWeight=FontWeight.Black, fontSize=11.sp)
                    }
                }
                // pick area when your turn
                if(phase=="play" && turn==0){
                    val victim=nextAlive(0)
                    if(victim!=null && hands[victim].isNotEmpty()){
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ArcadeTokens.SurfaceAlt).padding(10.dp), verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Text("Ambil dari Bot $victim — tap salah satu kartu tertutup:", fontWeight=FontWeight.Bold, fontSize=12.sp, color=ArcadeTokens.Accent)
                            Row(horizontalArrangement=Arrangement.spacedBy(6.dp), modifier=Modifier.fillMaxWidth()){
                                for(idx in hands[victim].indices){
                                    Box(Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2D3436)).border(1.dp, Color.White.copy(alpha=0.15f), RoundedCornerShape(10.dp)).clickable(enabled=!botThinking){ playerPick(victim, idx) }, contentAlignment=Alignment.Center){
                                        Text("?", color=Color.White, fontWeight=FontWeight.Black, fontSize=18.sp)
                                    }
                                }
                            }
                            Text("Kartu diambil acak posisinya — pilih yang hoki!", fontSize=10.sp, color=ArcadeTokens.TextMuted)
                        }
                    }
                }
                // your hand
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ArcadeTokens.Surface).border(if(turn==0) 2.dp else 1.dp, if(turn==0) ArcadeTokens.Primary else ArcadeTokens.BgMuted, RoundedCornerShape(14.dp)).padding(10.dp), verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                        Text("Kartu Kamu (${hands.getOrNull(0)?.size ?: 0})", fontWeight=FontWeight.Bold, fontSize=12.sp)
                        if(hands.getOrNull(0)?.any{it.isJoker}==true) Box(Modifier.clip(RoundedCornerShape(8.dp)).background(ArcadeTokens.Danger).padding(horizontal=8.dp, vertical=4.dp)){ Text("JOKER!", color=Color.White, fontWeight=FontWeight.Black, fontSize=11.sp) }
                    }
                    val hand=hands.getOrNull(0) ?: listOf()
                    if(hand.isEmpty() && phase!="idle"){
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment=Alignment.Center){ Text("Habis! Kamu aman 🎉", fontWeight=FontWeight.Black, color=ArcadeTokens.Primary) }
                    } else if(hand.isNotEmpty()){
                        Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                            for(chunk in hand.chunked(4)){
                                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                    for(c in chunk){
                                        Box(Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).border(1.dp, if(c.isJoker) ArcadeTokens.Danger else ArcadeTokens.BgMuted, RoundedCornerShape(10.dp)), contentAlignment=Alignment.Center){
                                            Text(c.label(), color=c.col, fontWeight=FontWeight.Black, fontSize=13.sp)
                                        }
                                    }
                                    repeat(4 - chunk.size){ Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                        Text("Pair harus angka sama + warna sama: ♥♦ merah / ♣♠ hitam. Joker tidak punya pair.", fontSize=10.sp, color=ArcadeTokens.TextMuted)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            when(phase){
                "idle","win"->{
                    Row(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Button(onClick={ deal() }, modifier=Modifier.weight(1f).height(48.dp)){ Text(if(phase=="win") "New Game" else "Deal") }
                        if(phase=="win") OutlinedButton(onClick={ hands=listOf(); phase="idle"; msg="Tap Deal — Old Maid Joker vs 3 bots" }, modifier=Modifier.height(48.dp)){ Text("Clear") }
                    }
                }
                else->{
                    if(botThinking) LinearProgressIndicator(modifier=Modifier.fillMaxWidth().padding(top=6.dp))
                    else if(turn!=0) { Text("Bot $turn jalan...", fontSize=11.sp, color=ArcadeTokens.TextMuted, modifier=Modifier.navigationBarsPadding()) }
                }
            }
        }
    }
    if(showHelp){
        AlertDialog(onDismissRequest={showHelp=false}, title={Text("Cara Main Old Maid")}, text={
            Column(verticalArrangement=Arrangement.spacedBy(6.dp), modifier=Modifier.verticalScroll(rememberScrollState())){
                Text("Tujuan: jangan pegang JOKER terakhir.", fontWeight=FontWeight.Bold, fontSize=13.sp)
                Text("• 53 kartu (52 + 1 Joker) dibagi 4. Tiap pemain langsung buang pair: angka SAMA + warna SAMA.\n  Merah = ♥ ♦  |  Hitam = ♣ ♠\n  Contoh pair: 7♥ + 7♦ (merah-merah ✓), Q♣ + Q♠ (hitam-hitam ✓)\n  7♥ + 7♣ (merah-hitam ✗) bukan pair.", fontSize=12.sp)
                Text("• Se-arah jarum jam, giliran ambil 1 kartu tertutup dari lawan sebelah. Kalau kartu baru bikin pair warna sama dengan kartumu, langsung buang pair itu.", fontSize=12.sp)
                Text("• Tap kartu tertutup Bot target saat giliran Kamu. Bot ambil acak.", fontSize=12.sp, color=ArcadeTokens.TextMuted)
                Text("• Game selesai saat sisa 1 kartu: yang pegang Joker KALAH. Yang sudah 0 kartu aman/menang. Menang +60 chips.", fontSize=11.sp, fontWeight=FontWeight.Bold)
                Text("• Joker tidak pernah bisa dibuang — hati-hati!", fontSize=11.sp, color=ArcadeTokens.Danger)
            }
        }, confirmButton={ TextButton(onClick={showHelp=false}){Text("Got it")} })
    }
}
