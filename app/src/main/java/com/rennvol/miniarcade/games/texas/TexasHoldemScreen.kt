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
    var best= -1 to listOf<Int>(); var bv= -1 to listOf<Int>()
    // 21 combos: brute force
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
    var deck by remember{ mutableStateOf(mutableListOf<TCard>()) }
    var board by remember{ mutableStateOf(listOf<TCard>()) }
    var revealed by remember{ mutableIntStateOf(0) } // 0..5
    var players by remember{ mutableStateOf(listOf<TPlayer>()) }
    var curBet by remember{ mutableIntStateOf(0) } // highest bet this street
    var msg by remember{ mutableStateOf("Tap Deal — Texas Hold'em vs 3 bots") }
    var phase by remember{ mutableStateOf("idle") } // idle, preflop, flop, turn, river, showdown
    var myBet by remember{ mutableIntStateOf(0) }
    var showHelp by remember{ mutableStateOf(false) }
    var dealing by remember{ mutableStateOf(false) }
    var raiseAmt by remember{ mutableIntStateOf(20) }

    LaunchedEffect(Unit){ try{ ctx.dataStore.data.collect{ chips=it[Prefs.ARCADE_CHIPS]?:1000 } }catch(_:Exception){} }
    suspend fun save(c:Int){ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=c } }catch(_:Exception){} }

    fun botAction(idx:Int, cur:Int):String = try{
        val p=players.getOrNull(idx)?: return "fold"
        if(p.folded || p.allIn) return "check"
        // ponytail: naive hole-strength preflop, real eval only >=5 cards; fill never crashes now
        val sev = p.hole + board.take(revealed.coerceIn(0, board.size))
        val strength = if(sev.size<5){
            val r = p.hole.map{it.rank}.maxOrNull()?:2
            when{ r>=13->2; r>=11->1; else->0}
        } else {
            val ev = try{ bestOf7((sev.take(7) + List((7-sev.size).coerceAtLeast(0)){ TCard(TSuit.Clubs,2)}).take(7)) }catch(_:Exception){ 0 to listOf(0) }
            ev.first
        }
        val toCall = cur - p.bet
        if(strength>=6) "raise"
        else if(strength>=3) if(toCall==0) "check" else "call"
        else if(strength>=1) if(toCall==0) "check" else if(toCall<=10) "call" else "fold"
        else if(toCall==0) "check" else if(toCall<=5 && kotlin.random.Random.nextBoolean()) "call" else "fold"
    } catch(_:Exception){ "check" }

    suspend fun runBotsAfterPlayer(){
        // each bot acts once after player; if bot raises, player must act again — handled by enabling buttons again
        val mutable=players.toMutableList()
        for(i in 1 until mutable.size){
            if(mutable[i].folded || mutable[i].allIn) continue
            delay(520)
            val act=botAction(i, curBet)
            when(act){
                "fold"-> mutable[i]=mutable[i].copy(folded=true)
                "call"->{
                    val need=curBet - mutable[i].bet
                    val pay=need.coerceAtMost(chips/3 + 40) // bots have infinite chips for simplicity, just need anim
                    // bots pay from pot perspective only; we don't track bot chips, just their bet
                    mutable[i]=mutable[i].copy(bet=curBet)
                    pot+=need.coerceAtLeast(0)
                }
                "raise"->{
                    val add=20
                    curBet+=add
                    mutable[i]=mutable[i].copy(bet=curBet)
                    pot+=add + (curBet-add - mutable[i].bet + add)
                    msg="Bot ${i} raises to $curBet — your turn: Call / Raise / Fold"
                }
                else->{}
            }
            players=mutable.toList()
            // reveal one by one animation for board already handled
        }
    }

    fun checkAdvance(){
        val active=players.count{!it.folded}
        if(active==1){
            // win by fold
            val winner=players.indexOfFirst{!it.folded}
            val win=pot
            if(winner==0){
                chips+=win; scope.launch{ save(chips) }
                msg="All fold — You win pot $win!"
            } else msg="You folded — Bot $winner wins pot $win"
            pot=0; phase="showdown"
            return
        }
        val allCalled = players.filter{!it.folded && !it.allIn}.all{ it.bet==curBet }
        if(!allCalled) return
        // advance street
        when(phase){
            "preflop"->{ phase="flop"; revealed=3; curBet=0; players=players.map{it.copy(bet=0)}; myBet=0; msg="Flop: ${board.take(3).joinToString(" "){it.label()}} — Check / Bet / Fold" }
            "flop"->{ phase="turn"; revealed=4; curBet=0; players=players.map{it.copy(bet=0)}; myBet=0; msg="Turn: ${board[3].label()} — Check / Bet / Fold" }
            "turn"->{ phase="river"; revealed=5; curBet=0; players=players.map{it.copy(bet=0)}; myBet=0; msg="River: ${board[4].label()} — Final bet: Check / Bet / Fold" }
            "river"->{
                // showdown
                val activePlayers=players.mapIndexed{ idx,p-> idx to p}.filter{!it.second.folded}
                val scores=activePlayers.map{ (idx,p)->
                    val sev=p.hole+board
                    val sc=bestOf7(sev)
                    idx to sc
                }
                val best=scores.maxByOrNull{ it.second.first*100 + (it.second.second.firstOrNull()?:0) } ?: run{ msg="No winner — draw"; pot=0; phase="showdown"; return }
                val winners=scores.filter{ compareHands(it.second, best.second)==0 }.map{it.first}
                val share=pot / winners.size
                if(0 in winners){
                    chips+=share; scope.launch{ save(chips) }
                    msg= if(winners.size==1) "WIN pot $pot! ${rankName(best.second.first)} — You win $share" else "SPLIT pot $pot — You win $share (${rankName(best.second.first)})"
                } else {
                    val w=winners.joinToString(","){ "Bot $it" }
                    msg="$w wins pot $pot with ${rankName(best.second.first)} — You lose"
                }
                pot=0; phase="showdown"
            }
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
            // animate deal one by one
            players=listOf(TPlayer(p0), TPlayer(p1), TPlayer(p2), TPlayer(p3))
            board=bd; revealed=0; pot=0; curBet=10; myBet=0; phase="preflop"
            if(players.any{ it.hole.size!=2 } || board.size!=5) throw IllegalStateException("deck deal failed")
            // blinds
            pot=15 // 5+10
            players=players.mapIndexed{ idx,p->
                when(idx){
                    1->p.copy(bet=5, total=5)
                    2->p.copy(bet=10, total=10)
                    else->p
                }
            }
            curBet=10
            msg="Pre-flop: Your ${p0.joinToString(" "){it.label()}} — Call 10 / Raise / Fold / All-in"
            // small delay then reveal loop not needed preflop
            dealing=false
            delay(160); revealed=0
        }
    }

    fun playerCheck(){
        if(phase=="showdown"||phase=="idle") return
        val mutable=players.toMutableList()
        val need=curBet - mutable[0].bet
        if(need>0){ msg="Need to Call $need or Fold — Check not allowed"; return }
        scope.launch{
            // check = bet 0
            runBotsAfterPlayer()
            checkAdvance()
        }
    }
    fun playerCall(){
        val mutable=players.toMutableList()
        val need=curBet - mutable[0].bet
        val pay=need.coerceAtMost(chips)
        if(pay<=0){ playerCheck(); return }
        chips-=pay; scope.launch{ save(chips) }
        pot+=pay
        mutable[0]=mutable[0].copy(bet=curBet, total=mutable[0].total+pay, allIn= chips==0)
        players=mutable.toList()
        myBet=curBet
        scope.launch{
            runBotsAfterPlayer()
            checkAdvance()
        }
    }
    fun playerRaise(){
        if(chips<=0) return
        val add=raiseAmt
        val newBet=curBet + add
        val need=newBet - players[0].bet
        if(need>chips) return
        chips-=need; scope.launch{ save(chips) }
        pot+=need
        curBet=newBet; myBet=newBet
        players=players.mapIndexed{ idx,p-> if(idx==0) p.copy(bet=newBet, total=p.total+need, allIn=chips==0) else p }
        msg="You raise to $newBet — bots to act"
        scope.launch{
            runBotsAfterPlayer()
            // if bots didn't raise further, we can auto-advance if all called
            checkAdvance()
        }
    }
    fun playerFold(){
        players=players.mapIndexed{ idx,p-> if(idx==0) p.copy(folded=true) else p }
        // bots win
        val win=pot
        msg="You folded — bots win pot $win"
        pot=0; phase="showdown"
    }
    fun playerAllIn(){
        if(chips<=0) return
        val need=chips
        val newBet=players[0].bet + need
        pot+=need
        curBet=maxOf(curBet, newBet)
        players=players.mapIndexed{ idx,p-> if(idx==0) p.copy(bet=newBet, total=p.total+need, allIn=true) else p }
        chips=0; scope.launch{ save(0) }
        msg="ALL-IN $newBet!"
        scope.launch{
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
            // scrollable middle
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(10.dp)){
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.PrimaryContainer).padding(10.dp)){
                    Text("Hold'em: 2 hole + 5 community (Flop 3 → Turn 1 → River 1). Best 5 of 7 wins. Check / Call / Bet / Raise / Fold / All-in vs 3 bots. Pot shared.", style=MaterialTheme.typography.labelMedium)
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
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if(phase=="showdown"||phase=="idle") ArcadeTokens.BgMuted else ArcadeTokens.PrimaryDark).padding(horizontal=10.dp, vertical=8.dp)){ Text("Bet to call: ${ (curBet - (players.getOrNull(0)?.bet?:0)).coerceAtLeast(0) }", color=Color.White, fontSize=11.sp, fontWeight=FontWeight.Bold) }
                }
                // board — reveal one by one
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0F3D2C)).padding(10.dp), verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("Board ${revealed}/5", color=Color.White, fontWeight=FontWeight.Bold, fontSize=12.sp)
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        for(i in 0..4){
                            val c=board.getOrNull(i)
                            val faceUp = i < revealed && c!=null && (phase!="idle" && board.isNotEmpty())
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
                // you
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ArcadeTokens.Surface).padding(10.dp), verticalArrangement=Arrangement.spacedBy(6.dp)){
                    val you=players.getOrNull(0)
                    val sev = if(you!=null && board.isNotEmpty() && revealed>=3) you.hole + board.take(revealed.coerceIn(0, board.size)) else you?.hole ?: listOf()
                    val best = if(sev.size>=5) try{ bestOf7((sev + List(7-sev.size){ TCard(TSuit.Clubs,2)}).take(7)) }catch(_:Exception){ null } else null
                    Text(if(you==null) "You: —" else "You: ${you.hole.joinToString(" "){it.label()}} ${if(you.folded)"(FOLD)" else ""} ${if(you.allIn)"(ALL-IN)" else ""}  • Bet ${you.bet}", fontWeight=FontWeight.Bold, fontSize=12.sp)
                    if(best!=null && revealed>=3 && you!=null && !you.folded) Text("Best: ${rankName(best.first)}", fontSize=11.sp, color=ArcadeTokens.TextMuted)
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        for(h in you?.hole ?: listOf()){
                            Box(Modifier.size(width=56.dp,height=76.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).border(2.dp, ArcadeTokens.Primary, RoundedCornerShape(10.dp)), contentAlignment=Alignment.Center){
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
                // bots
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ArcadeTokens.Surface).padding(10.dp), verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("Opponents (3 bots)", fontWeight=FontWeight.Bold, fontSize=12.sp)
                    for(i in 1..3){
                        val p=players.getOrNull(i)
                        val show = phase=="showdown" && p!=null && !p.folded
                        Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if(p?.folded==true) ArcadeTokens.DangerContainer else ArcadeTokens.BgMuted).padding(horizontal=8.dp, vertical=4.dp)){ Text("Bot $i ${if(p?.folded==true)"FOLD" else ""} ${if(p?.allIn==true)"ALL-IN" else ""} • Bet ${p?.bet?:0}", fontSize=11.sp, fontWeight=FontWeight.Bold) }
                            if(p!=null){
                                for(h in p.hole){
                                    Box(Modifier.size(width=44.dp,height=58.dp).clip(RoundedCornerShape(8.dp)).background(if(show) Color.White else Color(0xFF2D3436)).border(1.dp, Color.White.copy(alpha=0.3f), RoundedCornerShape(8.dp)), contentAlignment=Alignment.Center){
                                        if(show) Text(h.label(), color=h.col, fontWeight=FontWeight.Bold, fontSize=12.sp) else Text("?", color=Color.White.copy(alpha=0.7f))
                                    }
                                }
                                if(show){
                                    val sev=p.hole+board.take(minOf(5, board.size))
                                    val sc=try{ bestOf7(sev) }catch(_:Exception){ null }
                                    if(sc!=null) Text(rankName(sc.first), fontSize=10.sp, color=ArcadeTokens.TextMuted)
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
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.SurfaceAlt).padding(10.dp)){
                        Text("Showdown — Board: ${board.joinToString(" "){it.label()}}", fontSize=11.sp, color=ArcadeTokens.TextMuted)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // actions pinned bottom
            when(phase){
                "idle","showdown"->{
                    Row(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Button(onClick={ deal() }, enabled=!dealing && chips>0, modifier=Modifier.weight(1f).height(48.dp)){ Text(if(phase=="showdown") "New Hand" else "Deal (ante 10)") }
                        if(phase=="showdown") OutlinedButton(onClick={ board=listOf(); players=listOf(); pot=0; revealed=0; phase="idle"; msg="Tap Deal — Texas Hold'em vs 3 bots"; curBet=0; myBet=0 }, modifier=Modifier.height(48.dp)){ Text("Clear") }
                    }
                }
                else->{
                    val need=(curBet - (players.getOrNull(0)?.bet?:0)).coerceAtLeast(0)
                    val canCheck=need==0
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth().navigationBarsPadding()){
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()){
                            if(canCheck) Button(onClick={ playerCheck() }, modifier=Modifier.weight(1f).height(44.dp)){ Text("Check") }
                            else Button(onClick={ playerCall() }, modifier=Modifier.weight(1f).height(44.dp)){ Text("Call $need") }
                            Button(onClick={ playerRaise() }, enabled=chips>need, modifier=Modifier.weight(1f).height(44.dp)){ Text("Raise +$raiseAmt") }
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
                Text("2 hole cards + 5 community. Best 5 of 7 wins.", style=MaterialTheme.typography.bodyMedium)
                Text("Street: Pre-flop → Flop (3) → Turn (1) → River (1) → Showdown. Each street: Check / Call / Bet / Raise / Fold / All-in.", fontSize=12.sp)
                Text("Blinds 5/10 start, bots act after you one by one. If all fold, pot goes to last standing. Pot chips are shared arcade_chips.", fontSize=12.sp, color=ArcadeTokens.TextMuted)
                Text("≠ Poker lama: itu 5-Card Draw (5 kartu draw vs dealer). Hold'em ada lawan & board keluar satu-satu.", fontSize=11.sp, color=ArcadeTokens.TextMuted)
            }
        }, confirmButton={ TextButton(onClick={showHelp=false}){Text("Got it")}} )
    }
}
