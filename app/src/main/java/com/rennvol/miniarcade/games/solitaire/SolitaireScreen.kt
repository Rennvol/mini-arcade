package com.rennvol.miniarcade.games.solitaire

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.rennvol.miniarcade.data.dataStore
import com.rennvol.miniarcade.ui.theme.ArcadeTokens
import kotlinx.coroutines.launch

// Puzzle skill board model: separate logic from visuals
private enum class Suit { Hearts, Diamonds, Clubs, Spades }
private data class Card(val suit: Suit, val rank: Int, var faceUp: Boolean = false) {
    val color: Color get() = if(suit==Suit.Hearts||suit==Suit.Diamonds) ArcadeTokens.Danger else ArcadeTokens.Text
    val isRed: Boolean get() = suit==Suit.Hearts||suit==Suit.Diamonds
    fun label(): String { val r = when(rank){1->"A";11->"J";12->"Q";13->"K";else->"$rank"}; val s=when(suit){Suit.Hearts->"♥";Suit.Diamonds->"♦";Suit.Clubs->"♣";Suit.Spades->"♠"}; return r+s }
}
private data class SolState(
    val tableau: List<MutableList<Card>> = List(7){ mutableListOf() },
    val foundation: List<MutableList<Card>> = List(4){ mutableListOf() },
    val stock: MutableList<Card> = mutableListOf(),
    val waste: MutableList<Card> = mutableListOf()
)

private fun newDeck(): MutableList<Card> {
    val d = mutableListOf<Card>()
    for(s in Suit.values()) for(r in 1..13) d.add(Card(s,r,false))
    d.shuffle()
    return d
}
private fun deal(): SolState {
    val deck = newDeck()
    val tab = List(7){ mutableListOf<Card>() }
    for(col in 0..6){ repeat(col+1){ tab[col].add(deck.removeAt(0)) }; tab[col].last().faceUp=true }
    return SolState(tableau=tab, foundation=List(4){ mutableListOf() }, stock=deck, waste=mutableListOf())
}
private fun canPlaceOnTableau(card: Card, dest: List<Card>): Boolean {
    if(dest.isEmpty()) return card.rank==13
    val top = dest.last()
    if(!top.faceUp) return false
    return top.isRed != card.isRed && top.rank == card.rank+1
}
private fun canPlaceOnFoundation(card: Card, pile: List<Card>): Boolean {
    if(pile.isEmpty()) return card.rank==1
    val top = pile.last(); return top.suit==card.suit && top.rank+1==card.rank
}

@Composable
fun SolitaireScreen(onBack: ()->Unit){
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(deal()) }
    var wins by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Triple<String,Int,Int>?>(null) } // source, pileIdx, cardIdx
    var undoStack by remember { mutableStateOf(listOf<SolState>()) }
    var won by remember { mutableStateOf(false) }

    LaunchedEffect(Unit){
        ctx.dataStore.data.collect{ p-> wins = p[intPreferencesKey("solitaire_wins")]?:0 }
    }
    fun snapshot(): SolState {
        return SolState(
            tableau = state.tableau.map{ it.toMutableList() },
            foundation = state.foundation.map{ it.toMutableList() },
            stock = state.stock.toMutableList(),
            waste = state.waste.toMutableList()
        )
    }
    fun pushUndo(){ undoStack = undoStack + snapshot() }
    fun checkWin(){
        if(state.foundation.sumOf{it.size}==52 && !won){
            won=true; wins++
            scope.launch{ ctx.dataStore.edit{ it[intPreferencesKey("solitaire_wins")]=wins } }
        }
    }
    fun draw(){
        pushUndo()
        if(state.stock.isEmpty()){
            state = state.copy(stock = state.waste.reversed().onEach{it.faceUp=false}.toMutableList(), waste = mutableListOf())
        } else {
            val c = state.stock.removeAt(state.stock.lastIndex); c.faceUp=true; state.waste.add(c)
            state = state.copy()
        }
    }
    fun tryMoveToFoundation(from: String, pileIdx:Int, cardIdx:Int){
        val card = when(from){
            "waste"-> state.waste.lastOrNull() ?: return
            "tableau"-> state.tableau[pileIdx].getOrNull(cardIdx) ?: return
            else-> return
        }
        if(!card.faceUp) return
        // only single top card to foundation
        if(from=="tableau" && cardIdx != state.tableau[pileIdx].lastIndex) return
        for(fi in 0..3){
            if(canPlaceOnFoundation(card, state.foundation[fi])){
                pushUndo()
                when(from){
                    "waste"-> state.waste.removeAt(state.waste.lastIndex)
                    "tableau"-> { state.tableau[pileIdx].removeAt(cardIdx); if(state.tableau[pileIdx].isNotEmpty()) state.tableau[pileIdx].last().faceUp=true }
                }
                state.foundation[fi].add(card); state = state.copy(); checkWin(); return
            }
        }
    }
    fun tryMoveTableauToTableau(srcPile:Int, srcIdx:Int, dstPile:Int){
        if(srcPile==dstPile) return
        val moving = state.tableau[srcPile].subList(srcIdx, state.tableau[srcPile].size).toList()
        if(moving.any{!it.faceUp}) return
        val dest = state.tableau[dstPile]
        if(!canPlaceOnTableau(moving.first(), dest)) return
        pushUndo()
        repeat(moving.size){ state.tableau[srcPile].removeAt(srcIdx) }
        if(state.tableau[srcPile].isNotEmpty()) state.tableau[srcPile].last().faceUp=true
        state.tableau[dstPile].addAll(moving)
        state = state.copy()
    }

    Surface(color=ArcadeTokens.Bg, modifier=Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick=onBack, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.ArrowBack,"back") }
                Text("SOLITAIRE", style=MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    IconButton(onClick={
                        if(undoStack.isNotEmpty()){ state=undoStack.last(); undoStack=undoStack.dropLast(1); won=false }
                    }, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.Undo,"undo") }
                    Button(onClick={ state=deal(); undoStack=listOf(); won=false }, modifier=Modifier.height(44.dp), shape=RoundedCornerShape(12.dp)){ Text("New") }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.Surface).padding(10.dp), contentAlignment=Alignment.Center){ Text("Wins: $wins", style=MaterialTheme.typography.labelLarge) }
                if(won) Box(Modifier.clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.AccentContainer).padding(horizontal=12.dp, vertical=10.dp)){ Text("You win!", color=ArcadeTokens.Text) }
            }
            // stock/waste/foundation row
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                CardSlot(label="Stock ${state.stock.size}", faceUp=false, onClick={ draw() })
                CardSlot(label= state.waste.lastOrNull()?.label() ?: "—", faceUp= state.waste.isNotEmpty(), selected= selected?.first=="waste", onClick={
                    val w = state.waste.lastOrNull()
                    if(w!=null){
                        // single-tap: try foundation first, else select for tableau move
                        val before = state.foundation.sumOf{it.size}
                        tryMoveToFoundation("waste",0,0)
                        if(state.foundation.sumOf{it.size}>before){ selected=null; return@CardSlot }
                        // not movable to foundation -> select/deselect for tableau
                        selected = if(selected?.first=="waste") null else Triple("waste",0,0)
                    } else selected=null
                })
                Spacer(Modifier.weight(1f))
                for(fi in 0..3){
                    val top = state.foundation[fi].lastOrNull()
                    CardSlot(label= top?.label() ?: "A?", faceUp= top!=null, selected=false, onClick={})
                }
            }
            // tableau
            Row(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(6.dp)){
                for(col in 0..6){
                    Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(0.dp), horizontalAlignment=Alignment.CenterHorizontally){
                        Text("${col+1}", style=MaterialTheme.typography.labelSmall, color=ArcadeTokens.TextFaint)
                        Box(Modifier.fillMaxWidth().height(420.dp).clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.SurfaceAlt).padding(4.dp)){
                            Column(verticalArrangement=Arrangement.spacedBy((-18).dp)){
                                val pile = state.tableau[col]
                                if(pile.isEmpty()){
                                    Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, ArcadeTokens.Border, RoundedCornerShape(8.dp)).clickable{
                                        // try move selected here
                                        selected?.let{ (src, sPile, sIdx)->
                                            if(src=="waste"){
                                                val c = state.waste.lastOrNull() ?: return@clickable
                                                if(canPlaceOnTableau(c, pile)){ pushUndo(); state.waste.removeAt(state.waste.lastIndex); pile.add(c); state=state.copy(); selected=null }
                                            } else if(src=="tableau"){
                                                tryMoveTableauToTableau(sPile, sIdx, col); selected=null
                                            }
                                        }
                                    }, contentAlignment=Alignment.Center){ Text("K", color=ArcadeTokens.TextFaint) }
                                } else {
                                    for(idx in pile.indices){
                                        val c = pile[idx]
                                        val isSel = selected?.let{ it.first=="tableau" && it.second==col && it.third==idx } ?: false
                                        MiniCard(c, selected=isSel, onClick={
                                            if(!c.faceUp){
                                                if(idx==pile.lastIndex){ pushUndo(); c.faceUp=true; state=state.copy() }
                                                return@MiniCard
                                            }
                                            // tap to foundation if top
                                            if(idx==pile.lastIndex){
                                                // try foundation first
                                                val before = state.foundation.sumOf{it.size}
                                                tryMoveToFoundation("tableau",col,idx)
                                                if(state.foundation.sumOf{it.size}>before) { selected=null; return@MiniCard }
                                            }
                                            // selection logic
                                            if(selected==null) selected=Triple("tableau",col,idx)
                                            else {
                                                val (src, sPile, sIdx) = selected!!
                                                if(src=="tableau" && sPile==col && sIdx==idx) { selected=null; return@MiniCard }
                                                if(src=="tableau") { tryMoveTableauToTableau(sPile, sIdx, col); selected=null }
                                                else if(src=="waste"){
                                                    val wc = state.waste.lastOrNull() ?: return@MiniCard
                                                    if(canPlaceOnTableau(wc, pile)){ pushUndo(); state.waste.removeAt(state.waste.lastIndex); pile.add(wc); state=state.copy() }
                                                    selected=null
                                                }
                                            }
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Text("Tap stock to draw • Tap card to auto-move to foundation or select for tableau move • Undo available", style=MaterialTheme.typography.labelSmall, color=ArcadeTokens.TextFaint)
        }
    }
}

@Composable private fun CardSlot(label:String, faceUp:Boolean, selected:Boolean=false, onClick:()->Unit){
    Box(Modifier.size(width=48.dp,height=64.dp).clip(RoundedCornerShape(10.dp)).background(if(faceUp) ArcadeTokens.Surface else ArcadeTokens.BgMuted).border(if(selected) 2.dp else 1.dp, if(selected) ArcadeTokens.Primary else ArcadeTokens.Border, RoundedCornerShape(10.dp)).clickable(onClick=onClick), contentAlignment=Alignment.Center){
        Text(label, fontSize=12.sp, color= if(faceUp) ArcadeTokens.Text else ArcadeTokens.TextFaint, maxLines=1)
    }
}
@Composable private fun MiniCard(card: Card, selected:Boolean, onClick:()->Unit){
    val bg = when{
        !card.faceUp -> ArcadeTokens.BgMuted
        selected -> ArcadeTokens.PrimaryContainer
        else -> ArcadeTokens.Surface
    }
    val border = if(selected) ArcadeTokens.Primary else ArcadeTokens.Border
    Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp)).background(bg).border(1.dp, border, RoundedCornerShape(8.dp)).clickable(onClick=onClick), contentAlignment=Alignment.Center){
        if(card.faceUp) Column(horizontalAlignment=Alignment.CenterHorizontally){
            Text(card.label(), color=card.color, style=MaterialTheme.typography.labelLarge)
        } else {
            Box(Modifier.fillMaxSize().background(ArcadeTokens.Primary.copy(alpha=0.12f)), contentAlignment=Alignment.Center){ Text("?", color=ArcadeTokens.Primary) }
        }
    }
}
