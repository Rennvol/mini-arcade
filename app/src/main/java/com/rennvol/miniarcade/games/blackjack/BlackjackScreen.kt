package com.rennvol.miniarcade.games.blackjack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
private data class BCard(val suit:Suit,val rank:Int){
    fun label():String{ val r=when(rank){1->"A";11->"J";12->"Q";13->"K";else->"$rank"}; val s=when(suit){Suit.Hearts->"♥";Suit.Diamonds->"♦";Suit.Clubs->"♣";Suit.Spades->"♠"}; return r+s }
    val color:Color get()=if(suit==Suit.Hearts||suit==Suit.Diamonds) ArcadeTokens.Danger else ArcadeTokens.Text
    val value:Int get()=when(rank){1->11;11,12,13->10;else->rank}
}
private fun newDeck():MutableList<BCard>{ val d=mutableListOf<BCard>(); for(s in Suit.values()) for(r in 1..13) d.add(BCard(s,r)); d.shuffle(); return d }
private fun handValue(cards:List<BCard>):Int{
    var tot=cards.sumOf{it.value}
    var aces=cards.count{it.rank==1}
    while(tot>21 && aces>0){ tot-=10; aces-- }
    return tot
}
private fun isBlackjack(cards:List<BCard>)=cards.size==2 && handValue(cards)==21

@Composable
fun BlackjackScreen(onBack:()->Unit){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var chips by remember{ mutableIntStateOf(1000) }
    var bet by remember{ mutableIntStateOf(25) }
    var deck by remember{ mutableStateOf(mutableListOf<BCard>()) }
    var player by remember{ mutableStateOf(listOf<BCard>()) }
    var dealer by remember{ mutableStateOf(listOf<BCard>()) }
    var phase by remember{ mutableStateOf("bet") } // bet, playerTurn, dealerTurn, result
    var msg by remember{ mutableStateOf("Place bet & Deal") }
    var doubled by remember{ mutableStateOf(false) }

    LaunchedEffect(Unit){ ctx.dataStore.data.collect{ chips = it[Prefs.ARCADE_CHIPS] ?: 1000 } }
    suspend fun save(c:Int){ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=c } }

    fun deal(){
        if(chips<=0){ msg="Out of chips — New Game"; return }
        if(bet>chips) bet=chips
        val d=newDeck()
        player=listOf(d.removeAt(0), d.removeAt(0))
        dealer=listOf(d.removeAt(0), d.removeAt(0))
        deck=d; doubled=false
        val pv=handValue(player); val dv=handValue(dealer)
        if(isBlackjack(player) && isBlackjack(dealer)){ // push
            phase="result"; msg="Push — both Blackjack"
            scope.launch{ save(chips) }
        } else if(isBlackjack(player)){
            val win=(bet*3)/2
            chips+=win; phase="result"; msg="Blackjack! +$win (3:2)"
            scope.launch{ save(chips) }
        } else if(isBlackjack(dealer)){
            chips=(chips-bet).coerceAtLeast(0); phase="result"; msg="Dealer Blackjack — you lose -$bet"
            scope.launch{ save(chips) }
        } else {
            phase="playerTurn"; msg="Your $pv vs Dealer ? — Hit / Stand"
        }
    }
    fun hit(){
        if(phase!="playerTurn") return
        player=player+deck.removeAt(0)
        val v=handValue(player)
        if(v>21){
            chips=(chips-bet*(if(doubled)2 else 1)).coerceAtLeast(0)
            phase="result"; msg="Bust $v — lose -${bet*(if(doubled)2 else 1)}"
            scope.launch{ save(chips) }
        } else if(v==21){
            msg="21 — Stand?"
        } else msg="Your $v — Hit / Stand"
    }
    fun stand(){
        if(phase!="playerTurn") return
        phase="dealerTurn"
        var d=dealer.toMutableList()
        var dv=handValue(d)
        // dealer hits to 17 (stand on soft 17)
        while(dv<17){
            if(deck.isEmpty()) deck=newDeck()
            d.add(deck.removeAt(0)); dv=handValue(d)
        }
        dealer=d
        val pv=handValue(player)
        val mult=if(doubled) 2 else 1
        msg = when{
            dv>21 -> { chips+=bet*mult; "Dealer bust $dv — you win +${bet*mult}" }
            dv>pv -> { chips=(chips-bet*mult).coerceAtLeast(0); "Dealer $dv beats $pv — lose -${bet*mult}" }
            dv<pv -> { chips+=bet*mult; "You $pv beats Dealer $dv — win +${bet*mult}" }
            else -> "Push $pv — $dv"
        }
        scope.launch{ save(chips) }
        phase="result"
    }
    fun dbl(){
        if(phase!="playerTurn" || player.size!=2 || chips<bet*2) return
        doubled=true
        player=player+deck.removeAt(0)
        val v=handValue(player)
        if(v>21){
            chips=(chips-bet*2).coerceAtLeast(0); phase="result"; msg="Double bust $v — lose -${bet*2}"
            scope.launch{ save(chips) }
        } else {
            // auto stand after double
            var d=dealer.toMutableList()
            var dv=handValue(d)
            while(dv<17){ if(deck.isEmpty()) deck=newDeck(); d.add(deck.removeAt(0)); dv=handValue(d) }
            dealer=d
            msg = when{
                dv>21 -> { chips+=bet*2; "Dealer bust $dv — double win +${bet*2}" }
                dv>v -> { chips=(chips-bet*2).coerceAtLeast(0); "Dealer $dv beats $v — lose -${bet*2}" }
                dv<v -> { chips+=bet*2; "You $v beats $dv — double win +${bet*2}" }
                else -> "Push $v"
            }
            scope.launch{ save(chips) }
            phase="result"
        }
    }
    fun newHand(){
        if(chips<=0){ chips=1000; scope.launch{ save(1000) } }
        phase="bet"; msg="Place bet & Deal"; player=listOf(); dealer=listOf(); doubled=false
    }

    Surface(color=ArcadeTokens.Bg, modifier=Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick=onBack, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.ArrowBack,"back") }
                Text("BLACKJACK", style=MaterialTheme.typography.titleLarge)
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.Surface).padding(horizontal=12.dp, vertical=8.dp)){
                    Text("Chips $chips", style=MaterialTheme.typography.labelLarge, fontWeight=FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically){
                Text("Bet:", style=MaterialTheme.typography.labelLarge)
                for(b in listOf(10,25,50,100)){
                    val sel=bet==b
                    Surface(shape=RoundedCornerShape(10.dp), color=if(sel) ArcadeTokens.Primary else ArcadeTokens.Surface, modifier=Modifier.clickable(enabled=phase=="bet"){ bet=b.coerceAtMost(chips.coerceAtLeast(10)) }.height(44.dp)){
                        Box(Modifier.padding(horizontal=12.dp), contentAlignment=Alignment.Center){ Text("$b", color=if(sel) Color.White else ArcadeTokens.Text) }
                    }
                }
                Spacer(Modifier.weight(1f))
                if(chips<=0) Button(onClick={ newHand() }, modifier=Modifier.height(44.dp)){ Text("New Game") }
            }
            Text(msg, style=MaterialTheme.typography.labelLarge, color=ArcadeTokens.TextMuted)

            // dealer row
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ArcadeTokens.Surface).padding(10.dp), verticalArrangement=Arrangement.spacedBy(6.dp)){
                val dealerLabel = when(phase){
                    "playerTurn" -> "Dealer: ${dealer.firstOrNull()?.label() ?: "?"} ?"
                    "bet" -> "Dealer: —"
                    else -> "Dealer: ${dealer.joinToString(" "){it.label()}} • ${handValue(dealer)}"
                }
                Text(dealerLabel, style=MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    if(dealer.isEmpty()){
                        repeat(2){ BjCardView(null,false) }
                    } else {
                        for(i in dealer.indices){
                            val hide = phase=="playerTurn" && i==1
                            BjCardView(dealer[i], !hide)
                        }
                    }
                }
            }
            // player row
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ArcadeTokens.Surface).padding(10.dp), verticalArrangement=Arrangement.spacedBy(6.dp)){
                val pv = if(player.isNotEmpty()) handValue(player) else 0
                Text(if(player.isEmpty()) "You: —" else "You: ${player.joinToString(" "){it.label()}} • $pv${if(isBlackjack(player)) " Blackjack!" else ""}", style=MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    if(player.isEmpty()) repeat(2){ BjCardView(null,false) } else for(c in player) BjCardView(c,true)
                }
            }

            Spacer(Modifier.weight(1f))
            when(phase){
                "bet" -> Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button(onClick={ deal() }, enabled=chips>0, modifier=Modifier.weight(1f).height(48.dp)){ Text("Deal ($bet)") }
                }
                "playerTurn" -> Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button(onClick={ hit() }, modifier=Modifier.weight(1f).height(48.dp)){ Text("Hit") }
                    Button(onClick={ stand() }, modifier=Modifier.weight(1f).height(48.dp)){ Text("Stand") }
                    OutlinedButton(onClick={ dbl() }, enabled=player.size==2 && chips>=bet*2, modifier=Modifier.weight(1f).height(48.dp)){ Text("Double") }
                }
                else -> Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button(onClick={ newHand() }, modifier=Modifier.weight(1f).height(48.dp)){ Text(if(chips<=0) "New Game (1000)" else "New Hand") }
                }
            }
            if(chips<=0 && phase=="result"){
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.DangerContainer).padding(12.dp), contentAlignment=Alignment.Center){
                    Text("Game Over — out of chips", color=ArcadeTokens.Danger, fontWeight=FontWeight.Bold)
                }
            }
        }
    }
}

@Composable private fun BjCardView(card:BCard?, faceUp:Boolean){
    Box(Modifier.size(width=56.dp,height=76.dp).clip(RoundedCornerShape(10.dp))
        .background(if(faceUp) ArcadeTokens.Surface else ArcadeTokens.BgMuted)
        .border(1.dp, ArcadeTokens.Border, RoundedCornerShape(10.dp)), contentAlignment=Alignment.Center){
        if(card!=null && faceUp) Text(card.label(), color=card.color, fontWeight=FontWeight.Bold, fontSize=14.sp)
        else if(card!=null && !faceUp) Text("?", color=ArcadeTokens.TextFaint, fontWeight=FontWeight.Bold)
        else Text("—", color=ArcadeTokens.TextFaint)
    }
}
