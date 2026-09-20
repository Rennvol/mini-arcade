package com.rennvol.miniarcade.games.texas

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

private enum class TSuit{ Hearts,Diamonds,Clubs,Spades }
private data class TCard(val suit:TSuit,val rank:Int){
    fun label():String{ val r=when(rank){14->"A";13->"K";12->"Q";11->"J";else->"$rank"}; val s=when(suit){TSuit.Hearts->"♥";TSuit.Diamonds->"♦";TSuit.Clubs->"♣";TSuit.Spades->"♠"}; return r+s }
    val col:Color get()= if(suit==TSuit.Hearts||suit==TSuit.Diamonds) ArcadeTokens.Danger else Color.Black
}
private fun newDeck():MutableList<TCard>{ val d=mutableListOf<TCard>(); for(s in TSuit.values()) for(r in 2..14) d.add(TCard(s,r)); d.shuffle(); return d }
private fun eval5(cards:List<TCard>):Pair<Int,List<Int>>{
    val ranks=cards.map{it.rank}.sorted(); val suits=cards.map{it.suit}
    val flush=suits.distinct().size==1; val distinct=ranks.distinct()
    val straight:Boolean; val straightHigh:Int
    if(distinct.size==5){
        if(ranks==listOf(2,3,4,5,14)){ straight=true; straightHigh=5 }
        else if(ranks.max()-ranks.min()==4){ straight=true; straightHigh=ranks.max() }
        else{ straight=false; straightHigh=0 }
    } else{ straight=false; straightHigh=0 }
    val counts=ranks.groupBy{it}.mapValues{it.value.size}.entries.sortedWith(compareByDescending<Map.Entry<Int,Int>>{it.value}.thenByDescending{it.key})
    val vals=counts.map{it.key}; val cnts=counts.map{it.value}
    val rank:Int; val tie:List<Int>
    when{
        straight&&flush->{ rank=8; tie=listOf(straightHigh) }
        cnts[0]==4->{ rank=7; tie=listOf(vals[0], vals[1]) }
        cnts[0]==3&&cnts[1]==2->{ rank=6; tie=listOf(vals[0], vals[1]) }
        flush->{ rank=5; tie=ranks.sortedDescending() }
        straight->{ rank=4; tie=listOf(straightHigh) }
        cnts[0]==3->{ rank=3; tie=listOf(vals[0]) + vals.drop(1).sortedDescending() }
        cnts[0]==2&&cnts[1]==2->{ rank=2; tie=listOf(maxOf(vals[0],vals[1]), minOf(vals[0],vals[1]), vals[2]) }
        cnts[0]==2->{ rank=1; tie=listOf(vals[0]) + vals.drop(1).sortedDescending() }
        else->{ rank=0; tie=ranks.sortedDescending() }
    }
    return rank to tie
}
private fun bestOf7(seven:List<TCard>):Pair<Int,List<Int>>{
    var bv= -1 to listOf<Int>()
    for(a in 0..6) for(b in a+1..6) for(c in b+1..6) for(d in c+1..6) for(e in d+1..6) {
        val five=listOf(seven[a],seven[b],seven[c],seven[d],seven[e])
        val ev=eval5(five)
        if(ev.first>bv.first || (ev.first==bv.first && compareTie(ev.second,bv.second)>0)) bv=ev
    }
    return bv
}
private fun compareTie(a:List<Int>,b:List<Int>):Int{ for(i in a.indices){ if(i>=b.size) break; if(a[i]!=b[i]) return a[i].compareTo(b[i]) }; return 0 }
private fun compareHands(a:Pair<Int,List<Int>>, b:Pair<Int,List<Int>>):Int{
    if(a.first!=b.first) return a.first.compareTo(b.first)
    for(i in a.second.indices){ if(i>=b.second.size) break; if(a.second[i]!=b.second[i]) return a.second[i].compareTo(b.second[i]) }
    return 0
}
private fun rankName(r:Int)=when(r){8->"Straight Flush";7->"Four Kind";6->"Full House";5->"Flush";4->"Straight";3->"Three Kind";2->"Two Pair";1->"Pair";else->"High Card"}

private data class TPlayer(var hole:List<TCard>, var folded:Boolean=false, var bet:Int=0, var total:Int=0, var allIn:Boolean=false)

@Composable
fun TexasHoldemScreen(onBack:()->Unit){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var chips by remember{ mutableIntStateOf(1000) }
    var pot by remember{ mutableIntStateOf(0) }
    var board by remember{ mutableStateOf(listOf<TCard>()) }
    var revealed by remember{ mutableIntStateOf(0) }
    var players by remember{ mutableStateOf(listOf<TPlayer>()) }
    var curBet by remember{ mutableIntStateOf(0) }
    var msg by remember{ mutableStateOf("Tap Deal — Texas Hold'em vs 3 bots") }
    var phase by remember{ mutableStateOf("idle") }
    var showHelp by remember{ mutableStateOf(false) }
    var dealing by remember{ mutableStateOf(false) }
    var raiseAmt by remember{ mutableIntStateOf(20) }
    var activeTurn by remember{ mutableIntStateOf(-1) }
    var raisesThisStreet by remember{ mutableIntStateOf(0) }
    var raiseCapHit by remember{ mutableStateOf(false) }
    val maxRaises=4

    LaunchedEffect(Unit){ try{ ctx.dataStore.data.collect{ chips=it[Prefs.ARCADE_CHIPS]?:1000 } }catch(_:Exception){} }
    suspend fun save(c:Int){ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=c } }catch(_:Exception){} }

    fun botAction(idx:Int, cur:Int, raises:Int):String { return try{
        val p=players.getOrNull(idx)?: return "fold"
        if(p.folded || p.allIn) return "check"
        val sev = p.hole + board.take(revealed.coerceIn(0, board.size))
        val strength = if(sev.size<5){
            val r = p.hole.map{it.rank}.maxOrNull()?:2
            when{ r>=13->2; r>=11->1; else->0}
        } else {
            val ev = try{ bestOf7((sev.take(7) + List((7-sev.size).coerceAtLeast(0)){ TCard(TSuit.Clubs,2)}).take(7)) }catch(_:Exception){ 0 to listOf(0) }
            ev.first
        }
        val toCall = cur - p.bet
        val canRaise = raises < maxRaises
        if(strength>=6 && canRaise) "raise"
        else if(strength>=3) if(toCall==0) "check" else "call"
        else if(strength>=1) if(toCall==0) "check" else if(toCall<=10) "call" else "fold"
        else if(toCall==0) "check" else if(toCall<=5 && kotlin.random.Random.nextBoolean()) "call" else "fold"
    } catch(_:Exception){ "check" }
    }

    suspend fun runBotsAfterPlayer(){
        val mutable=players.toMutableList()
        for(i in 1 until mutable.size){
            if(mutable[i].folded || mutable[i].allIn) continue
            activeTurn=i
            delay(620)
            val act=botAction(i, curBet, raisesThisStreet)
            when(act){
                "fold"-> mutable[i]=mutable[i].copy(folded=true)
                "call"->{
                    val need=(curBet - mutable[i].bet).coerceAtLeast(0)
                    mutable[i]=mutable[i].copy(bet=curBet, total=mutable[i].total+need)
                    pot+=need
                }
                "raise"->{
                    val add=20
                    val newBet=curBet+add
                    val need=(newBet - mutable[i].bet).coerceAtLeast(0)
                    curBet=newBet
                    raisesThisStreet++
                    if(raisesThisStreet>=maxRaises) raiseCapHit=true
                    mutable[i]=mutable[i].copy(bet=newBet, total=mutable[i].total+need)
                    pot+=need
                    msg="Bot $i raises to $newBet — your turn: Call / Raise / Fold"
                }
                else->{}
            }
            players=mutable.toList()
        }
        // if player all-in, don't return turn to player — keep -1 and let auto flow handle
        activeTurn= if(players.getOrNull(0)?.allIn==true) -1 else 0
    }

    fun doShowdown(){
        val activePlayers=players.mapIndexed{ idx,p-> idx to p}.filter{!it.second.folded}
        if(activePlayers.isEmpty()){ msg="No winner"; pot=0; phase="showdown"; activeTurn=-1; revealed=5; return }
        val scores=activePlayers.map{ (idx,p)->
            val sev=p.hole+board
            val sc=try{ bestOf7(sev) }catch(_:Exception){ 0 to listOf(0) }
            idx to sc
        }
        val best=scores.maxWithOrNull(compareBy({it.second.first},{it.second.second.firstOrNull()?:0})) ?: run{ msg="No winner — draw"; pot=0; phase="showdown"; activeTurn=-1; revealed=5; return }
        val bestScore=best.second
        val winners=scores.filter{ compareHands(it.second, bestScore)==0 }.map{it.first}
        val share=pot / winners.size
        if(0 in winners){
            chips+=share; scope.launch{ save(chips) }
            msg= if(winners.size==1) "WIN pot $pot! ${rankName(bestScore.first)} — You win $share" else "SPLIT pot $pot — You win $share (${rankName(bestScore.first)})"
        } else {
            val w=winners.joinToString(","){ "Bot $it" }
            msg="$w wins pot $pot with ${rankName(bestScore.first)} — You lose"
        }
        pot=0; phase="showdown"; activeTurn=-1; revealed=5
    }

    fun checkAdvance(){
        val active=players.count{!it.folded}
        if(active==1){
            val winner=players.indexOfFirst{!it.folded}
            val win=pot
            if(winner==0){
                chips+=win; scope.launch{ save(chips) }
                msg="All fold — You win pot $win!"
            } else msg="You folded — Bot $winner wins pot $win"
            pot=0; phase="showdown"; activeTurn=-1; revealed=5
            return
        }
        // all-in fast-forward: if player all-in, skip remaining betting and deal board straight to showdown
        if(players.getOrNull(0)?.allIn==true){
            scope.launch{
                msg="ALL-IN — dealing remaining board..."
                activeTurn=-1
                // bots just call to match curBet (no extra raise when player all-in)
                // already handled in runBotsAfterPlayer, now just reveal
                if(phase=="preflop"){
                    for(t in 1..3){ delay(520); revealed=t }
                    delay(420); revealed=4
                    delay(420); revealed=5
                } else if(phase=="flop"){
                    if(revealed<3) for(t in (revealed+1)..3){ delay(520); revealed=t }
                    delay(420); revealed=4
                    delay(420); revealed=5
                } else if(phase=="turn"){
                    delay(520); revealed=4
                    delay(420); revealed=5
                } else if(phase=="river"){
                    delay(420); revealed=5
                }
                doShowdown()
            }
            return
        }
        val allCalled = players.filter{!it.folded && !it.allIn}.all{ it.bet==curBet }
        if(!allCalled) return
        when(phase){
            "preflop"->{
                phase="flop"; curBet=0; raisesThisStreet=0; raiseCapHit=false; players=players.map{it.copy(bet=0)}; msg="Flop — 3 kartu dibuka satu-satu..."
                scope.launch{
                    for(t in 1..3){ delay(520); revealed=t }
                    activeTurn=0; msg="Flop: ${board.take(3).joinToString(" "){it.label()}} — Check / Bet / Fold"
                }
            }
            "flop"->{
                phase="turn"; curBet=0; raisesThisStreet=0; raiseCapHit=false; players=players.map{it.copy(bet=0)}; msg="Turn — buka kartu ke-4..."
                scope.launch{ delay(520); revealed=4; activeTurn=0; msg="Turn: ${board[3].label()} — Check / Bet / Fold" }
            }
            "turn"->{
                phase="river"; curBet=0; raisesThisStreet=0; raiseCapHit=false; players=players.map{it.copy(bet=0)}; msg="River — buka kartu terakhir..."
                scope.launch{ delay(520); revealed=5; activeTurn=0; msg="River: ${board[4].label()} — Final bet: Check / Bet / Fold" }
            }
            "river"-> doShowdown()
            else->{}
        }
    }

    fun deal(){
        if(chips<=0){ msg="Out of chips — win more in Flappy/Paintball/Shooter!"; return }
        dealing=true
        scope.launch{
            val d=newDeck()
            val p0=d.subList(0,2).toList(); val p1=d.subList(2,4).toList(); val p2=d.subList(4,6).toList(); val p3=d.subList(6,8).toList()
            val bd=d.subList(8,13).toList()
            players=listOf(TPlayer(p0), TPlayer(p1), TPlayer(p2), TPlayer(p3))
            board=bd; revealed=0; pot=15; curBet=10; raisesThisStreet=1; raiseCapHit=false; phase="preflop"; activeTurn=0
            players=players.mapIndexed{ idx,p->
                when(idx){
                    1->p.copy(bet=5, total=5)
                    2->p.copy(bet=10, total=10)
                    else->p
                }
            }
            msg="Pre-flop: Your ${p0.joinToString(" "){it.label()}} — Call 10 / Raise / Fold / All-in"
            dealing=false
        }
    }

    fun playerCheck(){
        if(phase=="showdown"||phase=="idle") return
        if(players.getOrNull(0)?.allIn==true) return
        val need=curBet - (players.getOrNull(0)?.bet?:0)
        if(need>0){ msg="Need to Call $need or Fold — Check not allowed"; return }
        scope.launch{
            activeTurn=-1
            runBotsAfterPlayer()
            checkAdvance()
        }
    }
    fun playerCall(){
        if(players.getOrNull(0)?.allIn==true) return
        val mutable=players.toMutableList()
        if(mutable.isEmpty()) return
        val need=curBet - mutable[0].bet
        if(need<=0){ playerCheck(); return }
        val pay=need.coerceAtMost(chips)
        if(pay<=0) return
        chips-=pay; scope.launch{ save(chips) }
        pot+=pay
        val isAllIn = chips==0
        mutable[0]=mutable[0].copy(bet=mutable[0].bet+pay, total=mutable[0].total+pay, allIn=isAllIn)
        players=mutable.toList()
        scope.launch{
            activeTurn=-1
            runBotsAfterPlayer()
            checkAdvance()
        }
    }
    fun playerRaise(){
        if(players.getOrNull(0)?.allIn==true) return
        if(raisesThisStreet>=maxRaises){ msg="Cap $maxRaises bets/street — Call or Fold only"; return }
        if(chips<=0) return
        val add=raiseAmt
        val newBet=curBet + add
        val need=newBet - players[0].bet
        if(need>chips){ msg="Not enough chips ($chips) for raise $add — All-in?"; return }
        chips-=need; scope.launch{ save(chips) }
        pot+=need
        curBet=newBet; raisesThisStreet++
        if(raisesThisStreet>=maxRaises) raiseCapHit=true
        val isAllIn = chips==0
        players=players.mapIndexed{ idx,p-> if(idx==0) p.copy(bet=newBet, total=p.total+need, allIn=isAllIn) else p }
        msg="You raise to $newBet — bots to act"
        scope.launch{
            activeTurn=-1
            runBotsAfterPlayer()
            checkAdvance()
        }
    }
    fun playerFold(){
        if(players.getOrNull(0)?.allIn==true) return
        players=players.mapIndexed{ idx,p-> if(idx==0) p.copy(folded=true) else p }
        val win=pot
        msg="You folded — bots win pot $win"
        pot=0; phase="showdown"; activeTurn=-1; revealed=5
    }
    fun playerAllIn(){
        if(chips<=0) return
        if(players.getOrNull(0)?.allIn==true) return
        val need=chips
        val newBet=players[0].bet + need
        pot+=need
        curBet=maxOf(curBet, newBet)
        players=players.mapIndexed{ idx,p-> if(idx==0) p.copy(bet=newBet, total=p.total+need, allIn=true) else p }
        chips=0; scope.launch{ save(0) }
        msg="ALL-IN $newBet! — bots to match, board auto-deal..."
        scope.launch{
            activeTurn=-1
            runBotsAfterPlayer()
            checkAdvance()
        }
    }

    Surface(color=ArcadeTokens.Bg, modifier=Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(12.dp)){
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick=onBack, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.ArrowBack,"back") }
                Text("TEXAS HOLD'EM", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Black)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp), verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.Surface).padding(horizontal=12.dp, vertical=8.dp)){ Text("Chips $chips", fontWeight=FontWeight.Bold) }
                    IconButton(onClick={showHelp=true}, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.HelpOutline,"help") }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(10.dp)){
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.PrimaryContainer).padding(10.dp)){
                    Text("Hold'em: 2 hole + 5 community (Flop 3 → Turn 1 → River 1). Best 5 of 7 wins. Check / Call / Bet / Raise / Fold / All-in vs 3 bots. Pot shared. Cap $maxRaises bets/street.", style=MaterialTheme.typography.labelMedium)
                }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(
                    when{ msg.startsWith("WIN")||msg.contains("You win") -> ArcadeTokens.AccentContainer
                        msg.contains("fold") -> ArcadeTokens.DangerContainer
                        else -> ArcadeTokens.Surface }
                ).padding(10.dp), contentAlignment=Alignment.Center){
                    Text(msg, fontWeight=FontWeight.Bold, fontSize=12.sp, color=ArcadeTokens.Text)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.Surface).padding(horizontal=12.dp, vertical=8.dp)){ Text("Pot: $pot", fontWeight=FontWeight.Black, fontSize=14.sp) }
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.SurfaceAlt).padding(horizontal=10.dp, vertical=8.dp)){ Text(phase.uppercase(), fontWeight=FontWeight.Bold, fontSize=12.sp) }
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if(phase=="showdown"||phase=="idle") ArcadeTokens.BgMuted else ArcadeTokens.PrimaryDark).padding(horizontal=10.dp, vertical=8.dp)){ Text("Call: ${(curBet - (players.getOrNull(0)?.bet?:0)).coerceAtLeast(0)}", color=Color.White, fontSize=11.sp, fontWeight=FontWeight.Bold) }
                }
                if(raiseCapHit && phase!="idle" && phase!="showdown"){
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.DangerContainer).padding(8.dp), contentAlignment=Alignment.Center){
                        Text("Cap $maxRaises bets — no more raises this street", fontWeight=FontWeight.Bold, fontSize=11.sp, color=ArcadeTokens.Danger)
                    }
                }
                if(activeTurn>=0 && phase!="idle" && phase!="showdown"){
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if(activeTurn==0) ArcadeTokens.PrimaryContainer else ArcadeTokens.SurfaceAlt).padding(8.dp), contentAlignment=Alignment.Center){
                        Text(if(activeTurn==0) "▶ Your turn" else "⏳ Bot $activeTurn thinking...", fontWeight=FontWeight.Bold, fontSize=12.sp, color=ArcadeTokens.Text)
                    }
                }
                if(players.getOrNull(0)?.allIn==true && phase!="showdown" && phase!="idle"){
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.DangerContainer).padding(8.dp), contentAlignment=Alignment.Center){
                        Text("ALL-IN — no more actions, board dealing auto to showdown", fontWeight=FontWeight.Bold, fontSize=11.sp, color=ArcadeTokens.Danger)
                    }
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0F3D2C)).padding(10.dp), verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("Board ${revealed}/5 ${if(raiseCapHit)"• Cap" else ""}", color=Color.White, fontWeight=FontWeight.Bold, fontSize=12.sp)
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        for(i in 0..4){
                            val c=board.getOrNull(i)
                            val faceUp = i < revealed && c!=null && board.isNotEmpty() && phase!="idle"
                            Box(Modifier.size(width=56.dp,height=76.dp).clip(RoundedCornerShape(10.dp))
                                .background(if(faceUp) Color.White else Color(0xFF1A5C3A))
                                .border(1.dp, if(faceUp) Color.White else Color(0xFF2D7D4A), RoundedCornerShape(10.dp)),
                                contentAlignment=Alignment.Center){
                                if(faceUp && c!=null) Text(c.label(), color=c.col, fontWeight=FontWeight.Black, fontSize=14.sp)
                                else Text(if(board.isEmpty()) "—" else "?", color=Color.White.copy(alpha=0.6f), fontWeight=FontWeight.Bold)
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ArcadeTokens.Surface).border(if(activeTurn==0) 2.dp else 1.dp, if(activeTurn==0) ArcadeTokens.Primary else ArcadeTokens.BgMuted, RoundedCornerShape(14.dp)).padding(10.dp), verticalArrangement=Arrangement.spacedBy(6.dp)){
                    val you=players.getOrNull(0)
                    val sev = if(you!=null && board.isNotEmpty() && revealed>=3) you.hole + board.take(revealed.coerceIn(0, board.size)) else you?.hole ?: listOf()
                    val best = if(sev.size>=5) try{ bestOf7((sev + List(7-sev.size){ TCard(TSuit.Clubs,2)}).take(7)) }catch(_:Exception){ null } else null
                    Text(if(you==null) "You: —" else "You: ${you.hole.joinToString(" "){it.label()}} ${if(you.folded)"(FOLD)" else ""} ${if(you.allIn)"(ALL-IN)" else ""}  • Bet ${you.bet} • Total ${you.total}", fontWeight=FontWeight.Bold, fontSize=12.sp)
                    if(best!=null && revealed>=3 && you!=null && !you.folded) Text("Best: ${rankName(best.first)}", fontSize=11.sp, color=ArcadeTokens.TextMuted)
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        for(h in you?.hole ?: listOf()){
                            Box(Modifier.size(width=56.dp,height=76.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).border(2.dp, if(activeTurn==0) ArcadeTokens.Primary else ArcadeTokens.BgMuted, RoundedCornerShape(10.dp)), contentAlignment=Alignment.Center){
                                Text(h.label(), color=h.col, fontWeight=FontWeight.Black, fontSize=15.sp)
                            }
                        }
                        if(you==null){
                            repeat(2){
                                Box(Modifier.size(width=56.dp,height=76.dp).clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.BgMuted), contentAlignment=Alignment.Center){ Text("—", color=ArcadeTokens.TextFaint) }
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ArcadeTokens.Surface).padding(10.dp), verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("Opponents (3 bots)", fontWeight=FontWeight.Bold, fontSize=12.sp)
                    for(i in 1..3){
                        val p=players.getOrNull(i)
                        val show = phase=="showdown" && p!=null && !p.folded
                        val isTurn = activeTurn==i && phase!="showdown" && phase!="idle"
                        Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if(isTurn) ArcadeTokens.PrimaryContainer else Color.Transparent).border(if(isTurn) 2.dp else 0.dp, if(isTurn) ArcadeTokens.Primary else Color.Transparent, RoundedCornerShape(10.dp)).padding(4.dp)){
                            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if(p?.folded==true) ArcadeTokens.DangerContainer else if(isTurn) ArcadeTokens.Primary else ArcadeTokens.BgMuted).padding(horizontal=8.dp, vertical=4.dp)){ Text("Bot $i ${if(p?.folded==true)"FOLD" else ""} ${if(p?.allIn==true)"ALL-IN" else ""} • Bet ${p?.bet?:0} • Tot ${p?.total?:0}", fontSize=11.sp, fontWeight=FontWeight.Bold, color=if(isTurn) Color.White else ArcadeTokens.Text) }
                            if(p!=null){
                                for(h in p.hole){
                                    Box(Modifier.size(width=44.dp,height=58.dp).clip(RoundedCornerShape(8.dp)).background(if(show) Color.White else Color(0xFF2D3436)).border(1.dp, if(isTurn) ArcadeTokens.Primary else Color.White.copy(alpha=0.3f), RoundedCornerShape(8.dp)), contentAlignment=Alignment.Center){
                                        if(show) Text(h.label(), color=h.col, fontWeight=FontWeight.Bold, fontSize=12.sp) else Text("?", color=Color.White.copy(alpha=0.7f))
                                    }
                                }
                                if(show){
                                    val sev=p.hole+board.take(minOf(5, board.size))
                                    val sc=try{ bestOf7(sev) }catch(_:Exception){ null }
                                    if(sc!=null) Text(rankName(sc.first), fontSize=10.sp, color=ArcadeTokens.TextMuted)
                                } else if(p.folded){
                                    Text("Folded", fontSize=10.sp, color=ArcadeTokens.TextMuted)
                                }
                            } else {
                                repeat(2){
                                    Box(Modifier.size(width=44.dp,height=58.dp).clip(RoundedCornerShape(8.dp)).background(ArcadeTokens.BgMuted), contentAlignment=Alignment.Center){ Text("—", color=ArcadeTokens.TextFaint) }
                                }
                            }
                        }
                    }
                }
                if(phase=="showdown" && board.isNotEmpty()){
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.SurfaceAlt).padding(10.dp), verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text("Showdown — Board: ${board.joinToString(" "){it.label()}}", fontSize=11.sp, color=ArcadeTokens.TextMuted, fontWeight=FontWeight.Bold)
                        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                            for(c in board){
                                Box(Modifier.size(width=44.dp,height=58.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, ArcadeTokens.BgMuted, RoundedCornerShape(8.dp)), contentAlignment=Alignment.Center){
                                    Text(c.label(), color=c.col, fontWeight=FontWeight.Bold, fontSize=11.sp)
                                }
                            }
                        }
                        for(i in 1..3){
                            val p=players.getOrNull(i) ?: continue
                            if(p.folded) continue
                            val sev=p.hole+board
                            val sc=try{ bestOf7(sev) }catch(_:Exception){ null }
                            Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                Text("Bot $i:", fontSize=11.sp, fontWeight=FontWeight.Bold)
                                for(h in p.hole){
                                    Box(Modifier.size(width=40.dp,height=52.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, ArcadeTokens.BgMuted, RoundedCornerShape(8.dp)), contentAlignment=Alignment.Center){
                                        Text(h.label(), color=h.col, fontWeight=FontWeight.Bold, fontSize=11.sp)
                                    }
                                }
                                if(sc!=null) Text(rankName(sc.first), fontSize=11.sp, color=ArcadeTokens.PrimaryDark, fontWeight=FontWeight.Bold)
                            }
                        }
                        val you=players.getOrNull(0)
                        if(you!=null && !you.folded){
                            val sev=you.hole+board
                            val sc=try{ bestOf7(sev) }catch(_:Exception){ null }
                            Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                Text("You:", fontSize=11.sp, fontWeight=FontWeight.Bold)
                                for(h in you.hole){
                                    Box(Modifier.size(width=40.dp,height=52.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, ArcadeTokens.Primary, RoundedCornerShape(8.dp)), contentAlignment=Alignment.Center){
                                        Text(h.label(), color=h.col, fontWeight=FontWeight.Bold, fontSize=11.sp)
                                    }
                                }
                                if(sc!=null) Text(rankName(sc.first), fontSize=11.sp, color=ArcadeTokens.PrimaryDark, fontWeight=FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            when(phase){
                "idle","showdown"->{
                    Row(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Button(onClick={ deal() }, enabled=!dealing && chips>0, modifier=Modifier.weight(1f).height(48.dp)){ Text(if(phase=="showdown") "New Hand" else "Deal (ante 10)") }
                        if(phase=="showdown") OutlinedButton(onClick={ board=listOf(); players=listOf(); pot=0; revealed=0; phase="idle"; msg="Tap Deal — Texas Hold'em vs 3 bots"; curBet=0; activeTurn=-1; raisesThisStreet=0; raiseCapHit=false }, modifier=Modifier.height(48.dp)){ Text("Clear") }
                    }
                }
                else->{
                    val youAllIn = players.getOrNull(0)?.allIn==true
                    if(youAllIn){
                        Box(Modifier.fillMaxWidth().navigationBarsPadding().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.DangerContainer).padding(12.dp), contentAlignment=Alignment.Center){
                            Text("ALL-IN — waiting for board & showdown", fontWeight=FontWeight.Bold, color=ArcadeTokens.Danger)
                        }
                    } else {
                        val need=(curBet - (players.getOrNull(0)?.bet?:0)).coerceAtLeast(0)
                        val canCheck=need==0
                        val canRaise = raisesThisStreet < maxRaises && chips > need
                        Column(verticalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth().navigationBarsPadding()){
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()){
                                if(canCheck) Button(onClick={ playerCheck() }, modifier=Modifier.weight(1f).height(44.dp)){ Text("Check") }
                                else Button(onClick={ playerCall() }, enabled=chips>0, modifier=Modifier.weight(1f).height(44.dp)){ Text("Call $need") }
                                Button(onClick={ playerRaise() }, enabled=canRaise, modifier=Modifier.weight(1f).height(44.dp)){ Text("Raise +$raiseAmt") }
                            }
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()){
                                OutlinedButton(onClick={ playerFold() }, modifier=Modifier.weight(1f).height(44.dp)){ Text("Fold") }
                                Button(onClick={ playerAllIn() }, enabled=chips>0, modifier=Modifier.weight(1f).height(44.dp), colors=ButtonDefaults.buttonColors(containerColor=ArcadeTokens.Danger)){ Text("All-in ($chips)") }
                                Surface(shape=RoundedCornerShape(10.dp), color=ArcadeTokens.Surface, modifier=Modifier.clickable{ raiseAmt=if(raiseAmt==20) 50 else if(raiseAmt==50) 100 else 20 }.height(44.dp)){
                                    Box(Modifier.padding(horizontal=12.dp), contentAlignment=Alignment.Center){ Text("+$raiseAmt", fontWeight=FontWeight.Bold) }
                                }
                            }
                        }
                    }
                }
            }
            if(chips<=0 && phase=="showdown"){
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.DangerContainer).padding(12.dp), contentAlignment=Alignment.Center){
                    Text("Out of chips — farm more in Flappy / Paintball / Shooter • Wins restore chips", color=ArcadeTokens.Danger, fontWeight=FontWeight.Bold, fontSize=12.sp)
                }
            }
        }
    }
    if(showHelp){
        AlertDialog(onDismissRequest={showHelp=false}, title={Text("Texas Hold'em")}, text={
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                Text("2 hole + 5 community. Best 5 of 7. Cap $maxRaises bets/street — setelah cap cuma Call/Fold.", style=MaterialTheme.typography.bodyMedium)
                Text("ALL-IN: habis chips → gak bisa Check/Call/Bet lagi, board auto-deal satu-satu sampai showdown, gak minta coin lagi.", fontSize=12.sp)
                Text("Blinds 5/10, highlight turn, board dibuka 1-1 biar tempo pelan.", fontSize=12.sp, color=ArcadeTokens.TextMuted)
            }
        }, confirmButton={ TextButton(onClick={showHelp=false}){Text("Got it")}} )
    }
}
