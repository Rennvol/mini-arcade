package com.rennvol.miniarcade.games.poker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.launch

private enum class Suit{ Hearts,Diamonds,Clubs,Spades }
private data class PCard(val suit:Suit,val rank:Int){
    fun label():String{ val r=when(rank){14->"A";13->"K";12->"Q";11->"J";else->"$rank"}; val s=when(suit){Suit.Hearts->"♥";Suit.Diamonds->"♦";Suit.Clubs->"♣";Suit.Spades->"♠"}; return r+s }
    val color:Color get()=if(suit==Suit.Hearts||suit==Suit.Diamonds) ArcadeTokens.Danger else ArcadeTokens.Text
}
private fun newDeck():MutableList<PCard>{ val d=mutableListOf<PCard>(); for(s in Suit.values()) for(r in 2..14) d.add(PCard(s,r)); d.shuffle(); return d }
private fun eval(cards:List<PCard>):Pair<Int,List<Int>>{
    val ranks=cards.map{it.rank}.sorted()
    val suits=cards.map{it.suit}
    val flush=suits.distinct().size==1
    val distinct=ranks.distinct()
    val straight:Boolean; val straightHigh:Int
    if(distinct.size==5){
        if(ranks==listOf(2,3,4,5,14)){ straight=true; straightHigh=5 }
        else if(ranks.max()-ranks.min()==4){ straight=true; straightHigh=ranks.max() }
        else{ straight=false; straightHigh=0 }
    } else{ straight=false; straightHigh=0 }
    val counts=ranks.groupBy{it}.mapValues{it.value.size}.entries.sortedWith(compareByDescending<Map.Entry<Int,Int>>{it.value}.thenByDescending{it.key})
    val vals=counts.map{it.key}
    val cnts=counts.map{it.value}
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
private fun rankName(r:Int)=when(r){8->"Straight Flush";7->"Four of Kind";6->"Full House";5->"Flush";4->"Straight";3->"Three of Kind";2->"Two Pair";1->"Pair";else->"High Card"}
private fun compareHands(a:Pair<Int,List<Int>>, b:Pair<Int,List<Int>>):Int{
    if(a.first!=b.first) return a.first.compareTo(b.first)
    for(i in a.second.indices){ if(i>=b.second.size) break; if(a.second[i]!=b.second[i]) return a.second[i].compareTo(b.second[i]) }
    return 0
}
private fun dealerHolds(cards:List<PCard>):BooleanArray{
    val ev=eval(cards); val holds=BooleanArray(5){false}
    when(ev.first){
        8,6,5,4 -> return BooleanArray(5){true}
        7 -> { val q=cards.groupBy{it.rank}.maxBy{it.value.size}.key; cards.forEachIndexed{i,c-> if(c.rank==q) holds[i]=true }; return holds }
        3 -> { val t=cards.groupBy{it.rank}.maxBy{it.value.size}.key; cards.forEachIndexed{i,c-> if(c.rank==t) holds[i]=true }; return holds }
        2 -> { val pairs=cards.groupBy{it.rank}.filter{it.value.size==2}.keys; cards.forEachIndexed{i,c-> if(c.rank in pairs) holds[i]=true }; return holds }
        1 -> { val p=cards.groupBy{it.rank}.filter{it.value.size==2}.keys.first(); cards.forEachIndexed{i,c-> if(c.rank==p) holds[i]=true }; return holds }
        else -> { val high=cards.maxByOrNull{it.rank}; if(high!=null && high.rank>=12){ holds[cards.indexOfFirst{it.rank==high.rank}]=true }; return holds }
    }
}

private val rankRows = listOf(
    "Straight Flush" to "A-K-Q-J-10 same suit",
    "Four of Kind" to "4 same rank",
    "Full House" to "3+2 same ranks",
    "Flush" to "5 same suit",
    "Straight" to "5 sequential",
    "Three of Kind" to "3 same rank",
    "Two Pair" to "2+2 pairs",
    "Pair" to "2 same rank",
    "High Card" to "highest card"
)

@Composable
fun PokerScreen(onBack:()->Unit){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var chips by remember{ mutableIntStateOf(1000) }
    var bet by remember{ mutableIntStateOf(25) }
    var deck by remember{ mutableStateOf(mutableListOf<PCard>()) }
    var player by remember{ mutableStateOf(listOf<PCard>()) }
    var dealer by remember{ mutableStateOf(listOf<PCard>()) }
    var holds by remember{ mutableStateOf(BooleanArray(5){false}) }
    var phase by remember{ mutableStateOf("bet") }
    var msg by remember{ mutableStateOf("Place bet & tap Deal") }
    var delta by remember{ mutableStateOf(0) }
    var showHelp by remember{ mutableStateOf(false) }

    LaunchedEffect(Unit){
        try{ ctx.dataStore.data.collect{ chips = it[Prefs.ARCADE_CHIPS] ?: 1000 } } catch(_:Exception){}
    }
    suspend fun save(c:Int){ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=c } } catch(_:Exception){} }

    fun deal(){
        if(chips<=0){ msg="Out of chips — tap New Game"; return }
        if(bet>chips) bet=chips
        val d=newDeck()
        player=d.subList(0,5).toList(); dealer=d.subList(5,10).toList()
        deck=d.subList(10,d.size).toMutableList()
        holds=BooleanArray(5){false}
        phase="dealt"; msg="How to play: HOLD cards you want to keep, then tap Draw. Best hand wins."
        delta=0
    }
    fun draw(){
        val newPlayer=player.mapIndexed{i,c-> if(holds[i]) c else deck.removeAt(0) }
        player=newPlayer
        val dHolds=dealerHolds(dealer)
        val newDealer=dealer.mapIndexed{i,c-> if(dHolds[i]) c else deck.removeAt(0) }
        dealer=newDealer
        val pe=eval(player); val de=eval(dealer)
        val cmp=compareHands(pe,de)
        var newChips=chips
        when{
            cmp>0 -> { newChips=chips+bet; delta=bet; msg="WIN  +$bet  •  ${rankName(pe.first)} beats ${rankName(de.first)}" }
            cmp<0 -> { newChips=(chips-bet).coerceAtLeast(0); delta=-bet; msg="LOSE  -$bet  •  ${rankName(de.first)} beats ${rankName(pe.first)}" }
            else -> { delta=0; msg="PUSH  ±0  •  both ${rankName(pe.first)}" }
        }
        chips=newChips
        scope.launch{ save(newChips) }
        phase="result"
    }
    fun newHand(){ if(chips<=0){ chips=1000; scope.launch{ save(1000) } }; phase="bet"; msg="Place bet & tap Deal"; player=listOf(); dealer=listOf(); holds=BooleanArray(5){false}; delta=0 }

    Surface(color=ArcadeTokens.Bg, modifier=Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick=onBack, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.ArrowBack,"back") }
                Text("POKER", style=MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp), verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.Surface).padding(horizontal=12.dp, vertical=8.dp)){
                        Text("Chips $chips", style=MaterialTheme.typography.labelLarge, fontWeight=FontWeight.Bold)
                    }
                    IconButton(onClick={ showHelp=true }, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.HelpOutline,"help") }
                }
            }
            // tutorial banner
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.PrimaryContainer).padding(10.dp)){
                Text("How to play: HOLD cards you want to keep, then Draw. Best hand vs dealer wins. Earn chips to use in Poker/Blackjack.", style=MaterialTheme.typography.labelLarge, color=ArcadeTokens.Text)
            }
            // bet row
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically){
                Text("Bet:", style=MaterialTheme.typography.labelLarge)
                for(b in listOf(10,25,50,100)){
                    val sel=bet==b
                    val enabled = phase!="dealt" && chips>0 && b<=chips
                    Surface(shape=RoundedCornerShape(10.dp), color=if(sel) ArcadeTokens.Primary else ArcadeTokens.Surface, modifier=Modifier.clickable(enabled=enabled){ if(enabled) bet=b }.height(44.dp)){
                        Box(Modifier.padding(horizontal=12.dp), contentAlignment=Alignment.Center){ Text("$b", color=if(sel) Color.White else if(enabled) ArcadeTokens.Text else ArcadeTokens.TextFaint) }
                    }
                }
                Spacer(Modifier.weight(1f))
                if(chips<=0) Button(onClick={ newHand() }, modifier=Modifier.height(44.dp)){ Text("New Game") }
            }
            // payout / rank table
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.Surface).padding(10.dp)){
                Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
                    Text("Hand ranks (high → low)", style=MaterialTheme.typography.labelSmall, color=ArcadeTokens.TextFaint)
                    rankRows.forEach{ (name, ex) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween){
                            Text(name, fontSize=11.sp, fontWeight=FontWeight.SemiBold, color=ArcadeTokens.Text)
                            Text(ex, fontSize=11.sp, color=ArcadeTokens.TextMuted)
                        }
                    }
                    Text("Win +bet  •  Lose -bet  •  Push ±0", fontSize=11.sp, color=ArcadeTokens.TextMuted)
                }
            }
            // result banner
            val resultColor = when{ msg.startsWith("WIN") -> ArcadeTokens.AccentContainer; msg.startsWith("LOSE") -> ArcadeTokens.DangerContainer; else -> ArcadeTokens.Surface }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(resultColor).padding(10.dp), contentAlignment=Alignment.Center){
                Column(horizontalAlignment=Alignment.CenterHorizontally){
                    Text(msg, style=MaterialTheme.typography.labelLarge, color=ArcadeTokens.Text)
                    if(phase=="result" && delta!=0) Text(if(delta>0) "Chips +$delta" else "Chips $delta", fontSize=12.sp, color=ArcadeTokens.TextMuted)
                    if(phase!="bet" && player.isNotEmpty()) Text("Your hand: ${rankName(eval(player).first)}" + if(phase=="result") "  •  Dealer: ${rankName(eval(dealer).first)}" else "", fontSize=12.sp, color=ArcadeTokens.TextMuted)
                }
            }
            // dealer
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ArcadeTokens.Surface).padding(10.dp), verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text(if(phase=="result") "Dealer: ${dealer.joinToString(" "){it.label()}} • ${rankName(eval(dealer).first)}" else "Dealer: ? ? ? ? ?", style=MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    val showDealer=phase=="result"
                    for(i in 0..4){
                        val c=dealer.getOrNull(i)
                        PokerCardView(card=c, faceUp=showDealer, held=false, onClick={})
                    }
                }
            }
            // player
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ArcadeTokens.Surface).padding(10.dp), verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text(if(player.isNotEmpty()) "You: tap card to HOLD" else "You: —", style=MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    for(i in 0..4){
                        val c=player.getOrNull(i)
                        PokerCardView(card=c, faceUp=c!=null, held=holds.getOrElse(i){false}, onClick={
                            if(phase=="dealt"){ val nh=holds.copyOf(); nh[i]=!nh[i]; holds=nh }
                        })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                when(phase){
                    "bet" -> Button(onClick={ deal() }, enabled=chips>0, modifier=Modifier.weight(1f).height(48.dp)){ Text("Deal ($bet)") }
                    "dealt" -> {
                        Button(onClick={ draw() }, modifier=Modifier.weight(1f).height(48.dp)){ Text("Draw") }
                        OutlinedButton(onClick={ holds=BooleanArray(5){false} }, modifier=Modifier.height(48.dp)){ Text("Clear") }
                    }
                    "result" -> {
                        Button(onClick={ newHand() }, modifier=Modifier.weight(1f).height(48.dp)){ Text(if(chips<=0) "New Game (1000)" else "New Hand") }
                    }
                }
            }
            if(chips<=0 && phase=="result"){
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.DangerContainer).padding(12.dp), contentAlignment=Alignment.Center){
                    Text("Game Over — out of chips. Play Flappy Bird to earn more!", color=ArcadeTokens.Danger, fontWeight=FontWeight.Bold, fontSize=12.sp)
                }
            }
        }
    }
    if(showHelp){
        AlertDialog(onDismissRequest={ showHelp=false }, title={ Text("Poker — Hand Ranks") }, text={
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                Text("Hold cards you want to keep, tap Draw. Dealer also draws. Higher rank wins.", style=MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                rankRows.forEach{ (n,e)-> Text("$n — $e", fontSize=12.sp) }
                Spacer(Modifier.height(6.dp))
                Text("Tip: Flappy Bird score ×5 = chips earned. Use chips as Poker/Blackjack bankroll (shared arcade_chips).", fontSize=12.sp, color=ArcadeTokens.TextMuted)
            }
        }, confirmButton={ TextButton(onClick={ showHelp=false }){ Text("Got it") } })
    }
}

@Composable private fun PokerCardView(card:PCard?, faceUp:Boolean, held:Boolean, onClick:()->Unit){
    Box(Modifier.size(width=56.dp,height=76.dp).clip(RoundedCornerShape(10.dp))
        .background(if(faceUp) ArcadeTokens.Surface else ArcadeTokens.BgMuted)
        .border(if(held) 2.dp else 1.dp, if(held) ArcadeTokens.Primary else ArcadeTokens.Border, RoundedCornerShape(10.dp))
        .clickable(onClick=onClick), contentAlignment=Alignment.Center){
        if(card!=null && faceUp){
            Column(horizontalAlignment=Alignment.CenterHorizontally){
                Text(card.label(), color=card.color, fontWeight=FontWeight.Bold, fontSize=14.sp)
                if(held) Text("HOLD", fontSize=9.sp, color=ArcadeTokens.Primary, fontWeight=FontWeight.Bold)
            }
        } else if(card!=null && !faceUp){
            Text("?", color=ArcadeTokens.TextFaint, fontWeight=FontWeight.Bold)
        } else {
            Text("—", color=ArcadeTokens.TextFaint)
        }
    }
}
