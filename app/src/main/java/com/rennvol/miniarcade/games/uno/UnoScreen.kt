package com.rennvol.miniarcade.games.uno

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
import kotlin.random.Random

private enum class UColor(val display:String,val col:Color){ RED("Merah", Color(0xFFE53935)), GREEN("Hijau", Color(0xFF43A047)), BLUE("Biru", Color(0xFF1E88E5)), YELLOW("Kuning", Color(0xFFF9A825)), WILD("Wild", Color(0xFF212121)) }
private enum class UType{ NUMBER, SKIP, REVERSE, DRAW2, WILD, WILD4 }
private data class UCard(val color:UColor,val type:UType,val num:Int=-1){
    fun label():String = when(type){
        UType.NUMBER->"$num"
        UType.SKIP->"⊘"
        UType.REVERSE->"⇄"
        UType.DRAW2->"+2"
        UType.WILD->"W"
        UType.WILD4->"+4"
    }
    fun bg():Color = when(type){
        UType.WILD, UType.WILD4 -> Color(0xFF212121)
        else -> color.col
    }
}
private fun newDeck():MutableList<UCard>{
    val d=mutableListOf<UCard>()
    val cols=listOf(UColor.RED,UColor.GREEN,UColor.BLUE,UColor.YELLOW)
    for(c in cols){
        d.add(UCard(c,UType.NUMBER,0))
        for(n in 1..9){ repeat(2){ d.add(UCard(c,UType.NUMBER,n)) } }
        repeat(2){ d.add(UCard(c,UType.SKIP)); d.add(UCard(c,UType.REVERSE)); d.add(UCard(c,UType.DRAW2)) }
    }
    repeat(4){ d.add(UCard(UColor.WILD,UType.WILD)); d.add(UCard(UColor.WILD,UType.WILD4)) }
    d.shuffle(); return d
}
private fun canPlay(card:UCard, top:UCard, curColor:UColor):Boolean{
    if(card.type==UType.WILD||card.type==UType.WILD4) return true
    if(card.color==curColor) return true
    if(card.type==UType.NUMBER && top.type==UType.NUMBER && card.num==top.num) return true
    if(card.type!=UType.NUMBER && card.type==top.type) return true
    return false
}

@Composable
fun UnoScreen(onBack:()->Unit){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var chips by remember{ mutableIntStateOf(1000) }
    var hands by remember{ mutableStateOf(listOf<MutableList<UCard>>()) }
    var deck by remember{ mutableStateOf(mutableListOf<UCard>()) }
    var discard by remember{ mutableStateOf(mutableListOf<UCard>()) }
    var curColor by remember{ mutableStateOf(UColor.RED) }
    var turn by remember{ mutableIntStateOf(0) }
    var dir by remember{ mutableIntStateOf(1) }
    var pendingDraw by remember{ mutableIntStateOf(0) }
    var msg by remember{ mutableStateOf("Tap Deal — UNO vs 3 bots") }
    var phase by remember{ mutableStateOf("idle") }
    var botThinking by remember{ mutableStateOf(false) }
    var showHelp by remember{ mutableStateOf(false) }
    var wildPickFor by remember{ mutableStateOf<Int?>(null) } // hand index awaiting color
    var needColorPick by remember{ mutableStateOf(false) }

    LaunchedEffect(Unit){ try{ ctx.dataStore.data.collect{ chips=it[Prefs.ARCADE_CHIPS]?:1000 } }catch(_:Exception){} }
    suspend fun save(c:Int){ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=c } }catch(_:Exception){} }

    fun reshuffleIfNeeded(){
        if(deck.isEmpty() && discard.size>1){
            val top=discard.removeAt(discard.lastIndex)
            deck.addAll(discard.shuffled()); discard.clear(); discard.add(top)
        }
    }
    fun drawTo(p:Int,n:Int){
        repeat(n){
            reshuffleIfNeeded()
            if(deck.isNotEmpty()) hands[p].add(deck.removeAt(0))
        }
    }
    fun checkWin():Boolean{
        for(i in hands.indices) if(hands[i].isEmpty()){
            phase="win"
            if(i==0){ val win=60 + hands.filterIndexed{idx,_ -> idx!=0}.sumOf{ it.size }*4; chips+=win; scope.launch{ save(chips) }; msg="UNO WIN! Kamu habis duluan — +$win chips!" }
            else msg="Bot $i WIN — habis duluan. Kamu sisa ${hands[0].size} kartu."
            return true
        }
        return false
    }
    fun nextTurn(skip:Boolean=false){
        var step=if(skip)2 else 1
        turn = ((turn + dir*step) % 4 + 4) % 4
    }
    fun deal(){
        val d=newDeck()
        val h=listOf(mutableListOf<UCard>(), mutableListOf(), mutableListOf(), mutableListOf<UCard>())
        repeat(7){ for(p in 0..3) h[p].add(d.removeAt(0)) }
        var top=d.removeAt(0)
        // avoid starting with WILD4
        while(top.type==UType.WILD4){ d.add(top); d.shuffle(); top=d.removeAt(0) }
        val dis=mutableListOf(top)
        val cc=if(top.type==UType.WILD) listOf(UColor.RED,UColor.GREEN,UColor.BLUE,UColor.YELLOW).random() else top.color
        hands=h; deck=d; discard=dis; curColor=cc; turn=0; dir=1; pendingDraw=0; phase="play"; botThinking=false; wildPickFor=null; needColorPick=false
        var m="Mulai! Top ${top.label()} ${if(top.type==UType.WILD) "→ $cc" else top.color.display} — ${if(top.type==UType.SKIP) "Skip! " else ""}${if(top.type==UType.REVERSE) "Reverse! " else ""}${if(top.type==UType.DRAW2) "+2! " else ""}Giliran Kamu"
        if(top.type==UType.SKIP) turn=1
        if(top.type==UType.REVERSE){ dir=-1; turn=3 }
        if(top.type==UType.DRAW2){ pendingDraw=2 }
        msg=m
        // if wild starter ask color already random; no pick needed
    }

    fun botTurnLoop(){
        scope.launch{
            while(turn!=0 && phase=="play"){
                botThinking=true; delay(780)
                val hand=hands[turn]
                val top=discard.last()
                // if pending draw, must stack or draw
                if(pendingDraw>0){
                    val stackable=hand.filter{ (it.type==UType.DRAW2 || it.type==UType.WILD4) && canPlay(it, top, curColor) }
                    if(stackable.isNotEmpty()){
                        val card = stackable.firstOrNull{ it.type==UType.WILD4 } ?: stackable.first()
                        hand.remove(card); discard.add(card)
                        if(card.type==UType.WILD4){ curColor=listOf(UColor.RED,UColor.GREEN,UColor.BLUE,UColor.YELLOW).maxBy{ c-> hand.count{it.color==c} }; pendingDraw+=4; msg="Bot $turn stack ${card.label()} → $curColor (+$pendingDraw)" }
                        else { curColor=card.color; pendingDraw+=2; msg="Bot $turn stack +2 (+$pendingDraw)" }
                        if(hand.size==1) msg+=" — Bot $turn UNO!"
                        if(checkWin()){ botThinking=false; break }
                        nextTurn(); continue
                    } else {
                        drawTo(turn, pendingDraw); msg="Bot $turn ambil +$pendingDraw"; pendingDraw=0
                        nextTurn(); if(turn==0) msg+=" — giliran Kamu"; continue
                    }
                }
                val opts=hand.filter{ canPlay(it, top, curColor) }
                if(opts.isEmpty()){
                    drawTo(turn,1); msg="Bot $turn draw 1"
                    val drawn=hand.lastOrNull()
                    if(drawn!=null && canPlay(drawn, top, curColor)){
                        // auto play drawn if playable 55% chance
                        if(Random.nextInt(100)<55){
                            hand.remove(drawn); discard.add(drawn)
                            if(drawn.type==UType.WILD||drawn.type==UType.WILD4){
                                curColor=listOf(UColor.RED,UColor.GREEN,UColor.BLUE,UColor.YELLOW).maxBy{ c-> hand.count{it.color==c} }
                                if(drawn.type==UType.WILD4) pendingDraw+=4
                            } else curColor=drawn.color
                            when(drawn.type){
                                UType.SKIP->{ msg="Bot $turn draw & play ${drawn.label()} — Skip!"; if(checkWin()){botThinking=false;break}; nextTurn(skip=true) }
                                UType.REVERSE->{ dir*=-1; msg="Bot $turn draw & play ⇄ — Reverse!"; if(checkWin()){botThinking=false;break}; nextTurn() }
                                UType.DRAW2->{ pendingDraw+=2; msg="Bot $turn draw & play +2 — next +$pendingDraw"; if(checkWin()){botThinking=false;break}; nextTurn() }
                                UType.WILD->{ msg="Bot $turn draw & play W → $curColor"; if(checkWin()){botThinking=false;break}; nextTurn() }
                                UType.WILD4->{ pendingDraw=4; msg="Bot $turn draw & play +4 → $curColor (+4)"; if(checkWin()){botThinking=false;break}; nextTurn() }
                                else->{ msg="Bot $turn draw & play ${drawn.label()}"; if(checkWin()){botThinking=false;break}; nextTurn() }
                            }
                            if(hand.size==1) msg+=" — UNO!"
                            continue
                        }
                    }
                    nextTurn()
                } else {
                    // heuristic pick: prefer non-wild, keep wild for last
                    val scored=opts.map{ c->
                        var s=0
                        if(c.type==UType.WILD4) s+=100
                        if(c.type==UType.WILD) s+=80
                        if(c.type==UType.DRAW2) s-=10
                        if(c.type==UType.SKIP) s-=12
                        if(c.type==UType.REVERSE) s-=8
                        if(c.type==UType.NUMBER) s-= (9 - c.num)
                        // keep color diversity
                        s
                    }
                    val idx=scored.indices.minBy{ scored[it] }
                    val card=opts[idx]
                    hand.remove(card); discard.add(card)
                    when(card.type){
                        UType.WILD->{ curColor=listOf(UColor.RED,UColor.GREEN,UColor.BLUE,UColor.YELLOW).maxBy{ c-> hand.count{it.color==c} }; msg="Bot $turn W → $curColor"; if(checkWin()){botThinking=false;break}; nextTurn() }
                        UType.WILD4->{ curColor=listOf(UColor.RED,UColor.GREEN,UColor.BLUE,UColor.YELLOW).maxBy{ c-> hand.count{it.color==c} }; pendingDraw+=4; msg="Bot $turn +4 → $curColor (+$pendingDraw)"; if(checkWin()){botThinking=false;break}; nextTurn() }
                        UType.SKIP->{ curColor=card.color; msg="Bot $turn ⊘ Skip!"; if(checkWin()){botThinking=false;break}; if(hand.size==1) msg+=" — UNO!"; nextTurn(skip=true) }
                        UType.REVERSE->{ curColor=card.color; dir*=-1; msg="Bot $turn ⇄ Reverse!"; if(checkWin()){botThinking=false;break}; if(hand.size==1) msg+=" — UNO!"; nextTurn() }
                        UType.DRAW2->{ curColor=card.color; pendingDraw+=2; msg="Bot $turn +2 (+$pendingDraw)"; if(checkWin()){botThinking=false;break}; if(hand.size==1) msg+=" — UNO!"; nextTurn() }
                        else->{ curColor=card.color; msg="Bot $turn ${card.label()} ${card.color.display}"; if(checkWin()){botThinking=false;break}; if(hand.size==1) msg+=" — UNO!"; nextTurn() }
                    }
                }
                if(turn==0 && phase=="play") msg+=" — giliran Kamu"
            }
            botThinking=false
        }
    }

    fun playerDraw(){
        if(phase!="play"||turn!=0||botThinking) return
        if(needColorPick) return
        if(pendingDraw>0){
            drawTo(0, pendingDraw); msg="Kamu ambil +$pendingDraw"; pendingDraw=0; turn=1; scope.launch{ delay(380); botTurnLoop() }; return
        }
        val top=discard.lastOrNull()?:return
        drawTo(0,1); msg="Kamu draw 1 — ${hands[0].lastOrNull()?.let{ "${it.label()} ${if(it.type==UType.WILD||it.type==UType.WILD4) "Wild" else it.color.display}" } ?: ""}"
        // auto pass if still no playable? keep turn, allow play drawn
    }
    fun playerPlay(idx:Int){
        if(phase!="play"||turn!=0||botThinking||needColorPick) return
        if(idx !in hands[0].indices) return
        val card=hands[0][idx]
        val top=discard.last()
        if(pendingDraw>0){
            if(card.type!=UType.DRAW2 && card.type!=UType.WILD4){ msg="Ada +$pendingDraw — harus stack +2/+4 atau Draw"; return }
            if(!canPlay(card, top, curColor)){ msg="Tidak bisa stack kartu ini"; return }
        } else {
            if(!canPlay(card, top, curColor)){ msg="Tidak cocok — butuh warna $curColor atau angka/simbol sama"; return }
        }
        if(card.type==UType.WILD||card.type==UType.WILD4){
            wildPickFor=idx; needColorPick=true; msg="Pilih warna untuk ${card.label()}"
            return
        }
        // play immediately
        hands[0].removeAt(idx); discard.add(card)
        when(card.type){
            UType.SKIP->{ curColor=card.color; msg="Kamu ⊘ Skip!"; if(checkWin()) return; if(hands[0].size==1) msg+=" — UNO!"; nextTurn(skip=true); if(turn!=0) scope.launch{ delay(380); botTurnLoop() } }
            UType.REVERSE->{ curColor=card.color; dir*=-1; msg="Kamu ⇄ Reverse!"; if(checkWin()) return; if(hands[0].size==1) msg+=" — UNO!"; turn=((turn+dir)%4+4)%4; if(turn!=0) scope.launch{ delay(380); botTurnLoop() } }
            UType.DRAW2->{ curColor=card.color; pendingDraw+=2; msg="Kamu +2 — next +$pendingDraw"; if(checkWin()) return; if(hands[0].size==1) msg+=" — UNO!"; turn=1; if(pendingDraw==0) scope.launch{ delay(380); botTurnLoop() } else scope.launch{ delay(380); botTurnLoop() } }
            else->{ curColor=card.color; msg="Kamu ${card.label()}"; if(checkWin()) return; if(hands[0].size==1) msg+=" — UNO!"; turn=1; scope.launch{ delay(380); botTurnLoop() } }
        }
    }
    fun pickWildColor(col:UColor){
        val idx=wildPickFor?:return
        val card=hands[0][idx]
        hands[0].removeAt(idx); discard.add(card); curColor=col
        needColorPick=false; wildPickFor=null
        when(card.type){
            UType.WILD->{ msg="Kamu W → ${col.display}"; if(checkWin()) return; if(hands[0].size==1) msg+=" — UNO!"; turn=1; scope.launch{ delay(380); botTurnLoop() } }
            UType.WILD4->{ pendingDraw+=4; msg="Kamu +4 → ${col.display} (+$pendingDraw)"; if(checkWin()) return; if(hands[0].size==1) msg+=" — UNO!"; turn=1; scope.launch{ delay(380); botTurnLoop() } }
            else->{}
        }
    }

    Surface(color=ArcadeTokens.Bg, modifier=Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(12.dp)){
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick=onBack, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.ArrowBack,"back") }
                Text("UNO", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Black)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp), verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.Surface).padding(horizontal=12.dp, vertical=8.dp)){ Text("Chips $chips", fontWeight=FontWeight.Bold) }
                    IconButton(onClick={showHelp=true}, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.HelpOutline,"help") }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(10.dp)){
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.PrimaryContainer).padding(10.dp)){
                    Text("Buang kartu warna/angka sama. Wild ganti warna. Habiskan kartu duluan — teriak UNO di 1 kartu!", style=MaterialTheme.typography.labelMedium)
                }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(
                    when{ msg.contains("WIN") -> ArcadeTokens.AccentContainer
                        msg.contains("UNO") -> ArcadeTokens.SecondaryContainer
                        pendingDraw>0 -> ArcadeTokens.DangerContainer
                        else -> ArcadeTokens.Surface }
                ).padding(10.dp), contentAlignment=Alignment.Center){
                    Text(msg, fontWeight=FontWeight.Bold, fontSize=12.sp, color=ArcadeTokens.Text)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.Surface).padding(horizontal=10.dp, vertical=6.dp)){ Text("Warna: ${curColor.display}", fontWeight=FontWeight.Bold, fontSize=11.sp, color=curColor.col) }
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if(phase=="win") ArcadeTokens.Accent else if(turn==0) ArcadeTokens.Primary else ArcadeTokens.BgMuted).padding(horizontal=10.dp, vertical=6.dp)){ Text(if(phase=="win") "SELESAI" else if(turn==0) "▶ Kamu" else "⏳ Bot $turn ${if(dir==-1)"↺" else "↻"}", color=if(turn==0||phase=="win") Color.White else ArcadeTokens.Text, fontWeight=FontWeight.Bold, fontSize=11.sp) }
                    if(pendingDraw>0) Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.Danger).padding(horizontal=10.dp, vertical=6.dp)){ Text("+$pendingDraw", color=Color.White, fontWeight=FontWeight.Black, fontSize=11.sp) }
                }
                // bots
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    for(i in 1..3){
                        val isTurn=turn==i && phase=="play"
                        val cnt=hands.getOrNull(i)?.size ?: 7
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.Surface).border(if(isTurn) 2.dp else 1.dp, if(isTurn) ArcadeTokens.Primary else ArcadeTokens.BgMuted, RoundedCornerShape(12.dp)).padding(8.dp), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Text("Bot $i ${if(isTurn)"▶" else ""}", fontWeight=FontWeight.Bold, fontSize=11.sp, color=if(isTurn) ArcadeTokens.PrimaryDark else ArcadeTokens.Text)
                            Text("$cnt kartu", fontSize=11.sp, color=ArcadeTokens.TextMuted)
                            Row(horizontalArrangement=Arrangement.spacedBy(2.dp)){
                                repeat(minOf(cnt,6)){ Box(Modifier.size(width=14.dp,height=20.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF2D3436))) }
                                if(cnt>6) Text("+${cnt-6}", fontSize=9.sp, color=ArcadeTokens.TextFaint)
                            }
                            if(cnt==1) Text("UNO!", fontSize=10.sp, fontWeight=FontWeight.Black, color=ArcadeTokens.Danger)
                        }
                    }
                }
                // center: deck + discard
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1A3A2A)).padding(12.dp), horizontalArrangement=Arrangement.SpaceEvenly, verticalAlignment=Alignment.CenterVertically){
                    Column(horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Box(Modifier.size(width=72.dp,height=96.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2D3436)).border(2.dp, Color.White.copy(alpha=0.2f), RoundedCornerShape(12.dp)).clickable(enabled=phase=="play"&&turn==0&&!botThinking&&!needColorPick){ playerDraw() }, contentAlignment=Alignment.Center){
                            Column(horizontalAlignment=Alignment.CenterHorizontally){ Text("DECK", color=Color.White, fontWeight=FontWeight.Black, fontSize=12.sp); Text("${deck.size}", color=Color.White.copy(alpha=0.7f), fontSize=10.sp) }
                        }
                        Text("Tap Draw", color=Color.White.copy(alpha=0.6f), fontSize=9.sp)
                    }
                    Text("→", color=Color.White, fontSize=18.sp, fontWeight=FontWeight.Black)
                    Column(horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(4.dp)){
                        val top=discard.lastOrNull()
                        if(top!=null){
                            Box(Modifier.size(width=72.dp,height=96.dp).clip(RoundedCornerShape(12.dp)).background(top.bg()).border(2.dp, Color.White, RoundedCornerShape(12.dp)), contentAlignment=Alignment.Center){
                                Column(horizontalAlignment=Alignment.CenterHorizontally){ Text(top.label(), color=Color.White, fontWeight=FontWeight.Black, fontSize=22.sp); if(top.type==UType.NUMBER) Text(top.color.display, color=Color.White.copy(alpha=0.8f), fontSize=9.sp) else if(top.type==UType.WILD||top.type==UType.WILD4) Text(curColor.display, color=Color.White, fontSize=9.sp) }
                            }
                            Text("${if(top.type==UType.WILD||top.type==UType.WILD4) curColor.display else top.color.display} ${top.label()}", color=Color.White.copy(alpha=0.8f), fontSize=9.sp, fontWeight=FontWeight.Bold)
                        } else {
                            Box(Modifier.size(width=72.dp,height=96.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha=0.15f)), contentAlignment=Alignment.Center){ Text("—", color=Color.White) }
                        }
                    }
                }
                // wild picker
                if(needColorPick){
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.SurfaceAlt).padding(10.dp), verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text("Pilih warna Wild:", fontWeight=FontWeight.Bold, fontSize=12.sp)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()){
                            for(c in listOf(UColor.RED,UColor.GREEN,UColor.BLUE,UColor.YELLOW)){
                                Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp)).background(c.col).clickable{ pickWildColor(c) }, contentAlignment=Alignment.Center){ Text(c.display, color=Color.White, fontWeight=FontWeight.Black, fontSize=11.sp) }
                            }
                        }
                    }
                }
                // your hand
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ArcadeTokens.Surface).border(if(turn==0) 2.dp else 1.dp, if(turn==0) ArcadeTokens.Primary else ArcadeTokens.BgMuted, RoundedCornerShape(14.dp)).padding(10.dp), verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                        Text("Kartu Kamu (${hands.getOrNull(0)?.size ?: 0})", fontWeight=FontWeight.Bold, fontSize=12.sp)
                        if((hands.getOrNull(0)?.size ?: 0)==1) Box(Modifier.clip(RoundedCornerShape(8.dp)).background(ArcadeTokens.Danger).padding(horizontal=8.dp, vertical=4.dp)){ Text("UNO!", color=Color.White, fontWeight=FontWeight.Black, fontSize=11.sp) }
                    }
                    val hand=hands.getOrNull(0) ?: listOf()
                    if(hand.isEmpty() && phase!="idle"){
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment=Alignment.Center){ Text("Habis! 🎉", fontWeight=FontWeight.Black, color=ArcadeTokens.Primary) }
                    } else {
                        Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                            for(chunk in hand.indices.chunked(4)){
                                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                    for(i in chunk){
                                        val c=hand[i]
                                        val playable = if(phase=="play"&&turn==0&&!botThinking&&!needColorPick) canPlay(c, discard.lastOrNull() ?: c, curColor) && (pendingDraw==0 || c.type==UType.DRAW2 || c.type==UType.WILD4) else false
                                        Box(Modifier.weight(1f).height(78.dp).clip(RoundedCornerShape(10.dp)).background(c.bg()).border(if(playable) 2.dp else 1.dp, if(playable) Color.White else Color.Black.copy(alpha=0.12f), RoundedCornerShape(10.dp)).clickable(enabled=playable){ playerPlay(i) }, contentAlignment=Alignment.Center){
                                            Column(horizontalAlignment=Alignment.CenterHorizontally){
                                                Text(c.label(), color=Color.White, fontWeight=FontWeight.Black, fontSize=20.sp)
                                                Text(if(c.type==UType.NUMBER) c.color.display else when(c.type){UType.SKIP->"Skip";UType.REVERSE->"Reverse";UType.DRAW2->"+2";UType.WILD->"Wild";UType.WILD4->"Wild+4";else->""}, color=Color.White.copy(alpha=0.85f), fontSize=8.sp, fontWeight=FontWeight.Bold)
                                            }
                                        }
                                    }
                                    // fill remainder
                                    repeat(4 - chunk.size){ Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                        if(turn==0 && pendingDraw>0) Text("Stack +2/+4 untuk lawan atau Draw +$pendingDraw", fontSize=11.sp, color=ArcadeTokens.Danger, fontWeight=FontWeight.Bold)
                        else if(turn==0) Text("Tap kartu yang ada border putih untuk buang — warna/angka/simbol sama atau Wild", fontSize=11.sp, color=ArcadeTokens.TextMuted)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            when(phase){
                "idle","win"->{
                    Row(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Button(onClick={ deal() }, modifier=Modifier.weight(1f).height(48.dp)){ Text(if(phase=="win") "New Game" else "Deal") }
                        if(phase=="win") OutlinedButton(onClick={ hands=listOf(); discard=mutableListOf(); phase="idle"; msg="Tap Deal — UNO vs 3 bots" }, modifier=Modifier.height(48.dp)){ Text("Clear") }
                    }
                }
                else->{
                    Row(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        OutlinedButton(onClick={ playerDraw() }, enabled=turn==0 && !botThinking && !needColorPick, modifier=Modifier.weight(1f).height(48.dp)){ Text(if(pendingDraw>0) "Draw +$pendingDraw" else "Draw") }
                    }
                    if(botThinking) LinearProgressIndicator(modifier=Modifier.fillMaxWidth().padding(top=6.dp))
                }
            }
        }
    }
    if(showHelp){
        AlertDialog(onDismissRequest={showHelp=false}, title={Text("Cara Main UNO")}, text={
            Column(verticalArrangement=Arrangement.spacedBy(6.dp), modifier=Modifier.verticalScroll(rememberScrollState())){
                Text("Tujuan: habiskan kartu duluan.", fontWeight=FontWeight.Bold, fontSize=13.sp)
                Text("• Buang 1 kartu tiap giliran — harus sama WARNA atau ANGKA/SIMBOL dengan top deck, atau kartu Wild.", fontSize=12.sp)
                Text("• Wild (hitam) bisa kapan saja — pilih warna lanjut.", fontSize=12.sp)
                Text("• Kartu aksi:", fontWeight=FontWeight.Bold, fontSize=12.sp)
                Text("  ⊘ Skip — next player ke-skip giliran\n  ⇄ Reverse — arah putar berbalik\n  +2 — next harus ambil 2 (bisa stack +2 lain atau Wild+4 untuk oper)\n  W — Wild ganti warna\n  +4 — Wild + next ambil 4 (bisa stack +2/+4)", fontSize=11.sp)
                Text("• Stack: kalau kena +2/+4, kamu bisa timpuk +2/+4 lagi — jumlah numpuk, yang gak bisa stack harus Draw total.", fontSize=11.sp, color=ArcadeTokens.TextMuted)
                Text("• Draw: kalau gak ada kartu cocok, tap DECK untuk ambil 1. Kalau kartu baru cocok boleh langsung buang.", fontSize=11.sp, color=ArcadeTokens.TextMuted)
                Text("• UNO: sisa 1 kartu otomatis UNO! — lawan 3 bots, arah ↻/↺ kelihatan di badge giliran.", fontSize=11.sp, color=ArcadeTokens.PrimaryDark)
                Text("• Menang +60 + sisa lawan×4 chips.", fontSize=11.sp, fontWeight=FontWeight.Bold)
            }
        }, confirmButton={ TextButton(onClick={showHelp=false}){Text("Got it")} }
        )
    }
}
