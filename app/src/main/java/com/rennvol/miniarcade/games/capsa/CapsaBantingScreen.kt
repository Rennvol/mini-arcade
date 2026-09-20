package com.rennvol.miniarcade.games.capsa

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

private enum class CSuit(val order:Int, val sym:String){ Diamond(0,"♦"), Club(1,"♣"), Heart(2,"♥"), Spade(3,"♠") }
private data class CCard(val suit:CSuit,val rank:Int){ // 3..15 (15=2)
    fun label():String{ val r=when(rank){15->"2";14->"A";13->"K";12->"Q";11->"J"; else->"$rank"}; return r+suit.sym }
    val col:Color get()= if(suit==CSuit.Heart||suit==CSuit.Diamond) ArcadeTokens.Danger else Color.Black
}
private fun newDeck():MutableList<CCard>{ val d=mutableListOf<CCard>(); for(s in CSuit.values()) for(r in 3..15) d.add(CCard(s,r)); d.shuffle(); return d }
private fun cardCmp(a:CCard,b:CCard):Int{ if(a.rank!=b.rank) return a.rank.compareTo(b.rank); return a.suit.order.compareTo(b.suit.order) }
private fun sortHand(h: List<CCard>): List<CCard> = h.sortedWith(compareBy<CCard>{it.rank}.thenBy{it.suit.order})

// type rank: 1 single 2 pair 3 triple 4 straight 5 flush 6 fullhouse 7 four 8 straightflush
private data class Classed(val type:Int,val tie:List<Int>,val cards:List<CCard>)

private fun isFlush(c:List<CCard>):Boolean = c.all{it.suit==c[0].suit}
private fun isStraight(c:List<CCard>):Boolean{
    if(c.size!=5) return false
    val r=c.map{it.rank}.sorted()
    // 2 (15) cannot be in straight except 10 J Q K A? Big Two standard: A-2 not straight, but allow J Q K A 2 as straight? Simplify: straight must be consecutive without wrap, 2 is high so 10-J-Q-K-A is straight, 10-J-Q-K-2 is NOT
    // so straight high is max rank, check consecutive
    for(i in 1 until r.size) if(r[i]!=r[i-1]+1) return false
    return true
}
private fun classify(cards:List<CCard>):Classed?{
    if(cards.isEmpty()) return null
    val s=cards.sortedWith(compareBy<CCard>{it.rank}.thenBy{it.suit.order})
    when(s.size){
        1->{ val c=s[0]; return Classed(1, listOf(c.rank, c.suit.order), s) }
        2->{ if(s[0].rank!=s[1].rank) return null; val hi=maxOf(s[0],s[1], compareBy<CCard>{it.suit.order}); return Classed(2, listOf(s[0].rank, hi.suit.order), s) }
        3->{ if(s[0].rank!=s[1].rank||s[1].rank!=s[2].rank) return null; val hi=s.maxBy{it.suit.order}; return Classed(3, listOf(s[0].rank, hi.suit.order), s) }
        4->{
            val ranks=s.map{it.rank}
            val groups=ranks.groupBy{it}.mapValues{it.value.size}.entries.sortedWith(compareByDescending<Map.Entry<Int,Int>>{it.value}.thenByDescending{it.key})
            val cnt=groups.map{it.value}; val vals=groups.map{it.key}
            if(cnt==listOf(4)){ val q=vals[0]; val qs=s.filter{it.rank==q}.maxOf{it.suit.order}; return Classed(9, listOf(q, qs), s) }
            if(cnt==listOf(2,2)){ val hi=maxOf(vals[0],vals[1]); val lo=minOf(vals[0],vals[1]); val hs=s.filter{it.rank==hi}.maxOf{it.suit.order}; return Classed(10, listOf(hi, lo, hs), s) }
            return null
        }
        5->{
            val flush=isFlush(s)
            val straight=isStraight(s)
            val ranks=s.map{it.rank}
            val groups=ranks.groupBy{it}.mapValues{it.value.size}.entries.sortedWith(compareByDescending<Map.Entry<Int,Int>>{it.value}.thenByDescending{it.key})
            val cnt=groups.map{it.value}
            val vals=groups.map{it.key}
            if(straight&&flush){
                val hi=s.maxWith(compareBy<CCard>{it.rank}.thenBy{it.suit.order})
                return Classed(8, listOf(s.maxOf{it.rank}, hi.suit.order), s)
            }
            if(cnt==listOf(4,1)){
                // four + kicker
                val quadRank=vals[0]; val kick=vals[1]
                val quadSuit=s.filter{it.rank==quadRank}.maxOf{it.suit.order}
                return Classed(7, listOf(quadRank, quadSuit, kick), s)
            }
            if(cnt==listOf(3,2)){
                val trip=vals[0]; val pair=vals[1]
                return Classed(6, listOf(trip, pair), s)
            }
            if(flush){
                val hi=s.maxWith(compareBy<CCard>{it.rank}.thenBy{it.suit.order})
                // tie: sorted ranks desc + suit
                val tieRanks=ranks.sortedDescending()
                return Classed(5, tieRanks + hi.suit.order, s)
            }
            if(straight){
                val hi=s.maxWith(compareBy<CCard>{it.rank}.thenBy{it.suit.order})
                return Classed(4, listOf(s.maxOf{it.rank}, hi.suit.order), s)
            }
            return null
        }
        else-> return null
    }
}
private fun beats(a:Classed,b:Classed):Boolean{
    if(a.cards.size!=b.cards.size) return false
    if(a.cards.size==5){
        if(a.type!=b.type) return a.type > b.type
    } else {
        if(a.type!=b.type) return false // single vs pair not comparable
    }
    // same type, compare tie
    for(i in a.tie.indices){
        if(i>=b.tie.size) break
        if(a.tie[i]!=b.tie[i]) return a.tie[i] > b.tie[i]
    }
    return false
}

private fun findAllPlays(hand:List<CCard>, table:Classed?):List<Classed>{
    val out=mutableListOf<Classed>()
    val sorted=sortHand(hand)
    // singles
    for(c in sorted){ classify(listOf(c))?.let{ out.add(it) } }
    // pairs
    val byRank=sorted.groupBy{it.rank}
    for((_,g) in byRank) if(g.size>=2){ for(i in g.indices) for(j in i+1 until g.size){ classify(listOf(g[i],g[j]))?.let{ out.add(it) } } }
    // triples
    for((_,g) in byRank) if(g.size>=3){ for(i in g.indices) for(j in i+1 until g.size) for(k in j+1 until g.size){ classify(listOf(g[i],g[j],g[k]))?.let{ out.add(it) } } }
    // 5-card: brute combos if hand >=5 (C(13,5)=1287 max, ok)
    if(sorted.size>=5){
        // generate all 5 combos naively via indices
        val n=sorted.size
        for(a in 0 until n) for(b in a+1 until n) for(c in b+1 until n) for(d in c+1 until n) for(e in d+1 until n){
            val five=listOf(sorted[a],sorted[b],sorted[c],sorted[d],sorted[e])
            classify(five)?.let{ out.add(it) }
        }
    }
    // filter beats
    return if(table==null) out else out.filter{ beats(it, table) }
}

@Composable
fun CapsaBantingScreen(onBack:()->Unit){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var chips by remember{ mutableIntStateOf(1000) }
    var hands by remember{ mutableStateOf(listOf<List<CCard>>()) }
    var turn by remember{ mutableIntStateOf(0) }
    var table by remember{ mutableStateOf<Classed?>(null) }
    var lastPlayer:Int? by remember{ mutableStateOf<Int?>(null) }
    var passCount by remember{ mutableIntStateOf(0) }
    var msg by remember{ mutableStateOf("Tap Deal — Capsa Banting vs 3 bots") }
    var phase by remember{ mutableStateOf("idle") } // idle, play, win
    var selected by remember{ mutableStateOf(setOf<Int>()) }
    var showHelp by remember{ mutableStateOf(false) }
    var botThinking by remember{ mutableStateOf(false) }
    var difficulty by remember{ mutableIntStateOf(1) } // 0 easy 1 medium 2 hard

    LaunchedEffect(Unit){ try{ ctx.dataStore.data.collect{ chips=it[Prefs.ARCADE_CHIPS]?:1000 } }catch(_:Exception){} }
    suspend fun save(c:Int){ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=c } }catch(_:Exception){} }

    fun checkWin():Boolean{
        for(i in hands.indices) if(hands[i].isEmpty()){
            phase="win"
            if(i==0){ val win=50 + hands.filterIndexed{ idx,_ -> idx!=0 }.sumOf{ it.size }*5; chips+=win; scope.launch{ save(chips) }; msg="WIN! You habis duluan — +$win chips!" }
            else msg="Bot $i WIN — habis duluan. You sisa ${hands[0].size} kartu."
            return true
        }
        return false
    }

    fun launchBotTurn(){
        scope.launch{
            while(turn!=0 && phase=="play"){
                botThinking=true
                delay(760)
                val bh=hands[turn]
                if(bh.isEmpty()){ botThinking=false; break }
                val opts=findAllPlays(bh, table)
                if(opts.isEmpty()){
                    // pass
                    msg="Bot $turn PASS"
                    passCount++
                    if(passCount>=3 && lastPlayer!=null){
                        // table cleared
                        delay(420)
                        table=null; lastPlayer=null; passCount=0
                        msg="Meja clear — Bot $turn bebas buka kartu apa aja"
                    }
                    turn=(turn+1)%4
                    // if cleared and next is bot, it will auto play smallest
                    if(table==null && turn!=0){
                        delay(360)
                        val freeOpts=findAllPlays(hands[turn], null)
                        val play=freeOpts.minWithOrNull(compareBy<Classed>({it.type*10 + it.cards.size},{it.tie.firstOrNull()?:0})) ?: freeOpts.firstOrNull()
                        if(play!=null){
                            val nh=hands[turn].toMutableList(); play.cards.forEach{ nh.remove(it) }
                            hands=hands.toMutableList().also{ it[turn]=sortHand(nh) }.toList()
                            table=play; lastPlayer=turn; passCount=0
                            msg="Bot $turn buka ${play.cards.joinToString(" "){it.label()}}"
                            if(checkWin()){ botThinking=false; break }
                            turn=(turn+1)%4
                        }
                    }
                } else {
                    // hard bot: pick smallest winning, but bluff-keep bomb occasionally
                    // prefer low type, don't waste straight flush early unless must
                    val sortedOpts=opts.sortedWith(compareBy<Classed>({it.type},{it.tie.firstOrNull()?:0},{it.tie.getOrNull(1)?:0}))
                    // if multiple, 12% bluff with second smallest if not bomb
                    val bluffPct = when(difficulty){0->45;1->18; else->8}
                    val multiBias = difficulty==2
                    // hard bot: prefer multi-card (pair/triple/four) when it wins big and leaves fewer cards
                    val pick = if(sortedOpts.size>=2 && sortedOpts[0].type<7 && kotlin.random.Random.nextInt(100)<bluffPct) sortedOpts[1] else if(multiBias && sortedOpts.any{ it.type==3 || it.type==7 }){
                        sortedOpts.firstOrNull{ it.type==7 } ?: sortedOpts.firstOrNull{ it.type==3 } ?: sortedOpts[0]
                    } else sortedOpts[0]
                    val play=pick
                    val nh=bh.toMutableList(); play.cards.forEach{ nh.remove(it) }
                    hands=hands.toMutableList().also{ it[turn]=sortHand(nh) }.toList()
                    table=play; lastPlayer=turn; passCount=0
                    msg="Bot $turn main ${play.cards.joinToString(" "){it.label()}} (${when(play.type){1->"Single";2->"Pair";3->"Triple";9->"Four";10->"DoublePair";4->"Straight";5->"Flush";6->"Full House";7->"Four+1";8->"Straight Flush";else->""}})"
                    if(checkWin()){ botThinking=false; break }
                    // MULTI-PLAY TRIGGER: medium/hard bot can chain 2-4 kartu in one turn
                    val canChain = difficulty>=1 && play.cards.size<4
                    if(canChain){
                        var chain=play.cards.size
                        var chained=false
                        while(chain<4){
                            val next= hands.getOrNull(turn)?:break
                            if(next.isEmpty()) break
                            val opts2=findAllPlays(next, table)
                            if(opts2.isEmpty()) break
                            val p2=opts2.sortedWith(compareBy<Classed>({it.type},{it.tie.firstOrNull()?:0}))[0]
                            if(p2.cards.size + chain > 4) break
                            val nh2=next.toMutableList(); p2.cards.forEach{ nh2.remove(it) }
                            hands=hands.toMutableList().also{ it[turn]=sortHand(nh2) }.toList()
                            table=p2; chain+=p2.cards.size; chained=true
                            msg+=" -> chain " + p2.cards.joinToString(" "){it.label()}
                            if(checkWin()){ botThinking=false; break }
                        }
                        if(chained) msg+=" (chain " + chain + " kartu)"
                    }
                    turn=(turn+1)%4
                }
                if(turn==0) msg+=" — giliran Kamu ${if(table==null)"(bebas)" else "(kalahkan ${table!!.cards.joinToString(" "){it.label()}})"}"
            }
            botThinking=false
        }
    }

    fun deal(){
        val d=newDeck()
        val h=listOf(
            sortHand(d.subList(0,13).toList()),
            sortHand(d.subList(13,26).toList()),
            sortHand(d.subList(26,39).toList()),
            sortHand(d.subList(39,52).toList())
        )
        hands=h
        table=null; lastPlayer=null; passCount=0; selected=emptySet(); phase="play"; botThinking=false
        // who has 3♦ starts
        var starter=0
        for(i in h.indices) if(h[i].any{it.rank==3 && it.suit==CSuit.Diamond}){ starter=i; break }
        turn=starter
        msg= if(starter==0) "Kamu pegang 3♦ — mulai duluan! Pilih 1/2/3/4 kartu lalu Play" else "Bot $starter pegang 3♦ — Bot mulai"
        if(starter!=0){
            scope.launch{
                delay(700)
                // bot starter must play hand containing 3♦
                val bh=h[starter]
                val candidates=findAllPlays(bh, null).filter{cl-> cl.cards.any{it.rank==3 && it.suit==CSuit.Diamond}}
                val play = candidates.minWithOrNull(compareBy<Classed>({it.type},{it.tie.firstOrNull()?:0})) ?: candidates.firstOrNull() ?: classify(listOf(bh.first{it.rank==3 && it.suit==CSuit.Diamond}))!!
                val newHand=bh.toMutableList(); play.cards.forEach{ newHand.remove(it) }
                hands=hands.toMutableList().also{ it[starter]=sortHand(newHand) }.toList()
                table=play; lastPlayer=starter; turn=(starter+1)%4; passCount=0
                msg="Bot $starter buka ${play.cards.joinToString(" "){it.label()}} — giliran ${if(turn==0)"Kamu" else "Bot $turn"}"
                if(checkWin()) return@launch
                if(turn!=0) launchBotTurn()
            }
        }
    }

    fun playerPass(){
        if(phase!="play"||turn!=0||botThinking) return
        if(table==null){ msg="Meja kosong — harus buka kartu, gak bisa pass"; return }
        msg="Kamu PASS"
        passCount++
        if(passCount>=3 && lastPlayer!=null){
            scope.launch{
                delay(380)
                table=null; lastPlayer=null; passCount=0
                msg="Meja clear — kamu bebas buka"
            }
        }
        turn=1
        scope.launch{ delay(380); launchBotTurn() }
    }
    fun playerPlay(){
        if(phase!="play"||turn!=0||botThinking) return
        if(selected.isEmpty()) return
        val hand=hands.getOrNull(0)?:return
        if(selected.any{ it>=hand.size || it<0 }) return
        val picked=selected.map{ hand[it] }
        val cl=classify(picked)
        if(cl==null){ msg="Kombinasi tidak valid — Single / Pair / Triple / 5-kartu (Straight/Flush/FullHouse/Four/StraightFlush) saja"; return }
        if(table!=null && !beats(cl, table!!)){ msg="Harus kalahkan ${table!!.cards.joinToString(" "){it.label()}} — ${when(table!!.type){1->"Single lebih tinggi";2->"Pair lebih tinggi";3->"Triple lebih tinggi";else->"5-kartu ${when(table!!.type){4->"Straight";5->"Flush";6->"FullHouse";7->"Four";8->"StraightFlush";else->""}} lebih tinggi"}}"; return }
        // meja kosong but first move must contain 3♦ if you are starter and table null? Enforce if deal just started and you are starter
        // we already enforce bot starter; for player starter table null, allow any but hint 3♦
        val nh=hand.toMutableList(); cl.cards.forEach{ nh.remove(it) }
        hands=hands.toMutableList().also{ it[0]=sortHand(nh) }.toList()
        table=cl; lastPlayer=0; passCount=0; selected=emptySet()
        msg="Kamu main ${cl.cards.joinToString(" "){it.label()}}"
        if(checkWin()) return
        turn=1
        scope.launch{ delay(420); launchBotTurn() }
    }
    fun playerChain(){
        if(phase!="play"||turn!=0||botThinking) return
        if(selected.isEmpty() || selected.size>=4) return
        val hand=hands.getOrNull(0)?:return
        // guard stale/out-of-range indices (crash source)
        if(selected.any{ it>=hand.size || it<0 }) return
        val picked=selected.map{ hand[it] }
        val cl=classify(picked) ?: run{ msg="Kombinasi chain tidak valid"; return }
        if(table!=null && !beats(cl, table!!)){ msg="Chain harus kalahkan meja"; return }
        val nh=hand.toMutableList(); cl.cards.forEach{ nh.remove(it) }
        hands=hands.toMutableList().also{ it[0]=sortHand(nh) }.toList()
        table=cl; lastPlayer=0; passCount=0; selected=emptySet()
        var chain=cl.cards.size
        msg="Kamu chain ${cl.cards.joinToString(" "){it.label()}}"
        while(chain<4){
            val next=hands.getOrNull(0)?:break
            if(next.isEmpty()) break
            val opts=findAllPlays(next, table)
            if(opts.isEmpty()) break
            val p2=opts.sortedWith(compareBy<Classed>({it.type},{it.tie.firstOrNull()?:0}))[0]
            if(p2.cards.size + chain > 4) break
            val nh2=next.toMutableList(); p2.cards.forEach{ nh2.remove(it) }
            hands=hands.toMutableList().also{ it[0]=sortHand(nh2) }.toList()
            table=p2; chain+=p2.cards.size
            msg+=" -> chain ${p2.cards.joinToString(" "){it.label()}}"
            if(checkWin()) return
        }
        msg+=" (chain $chain kartu)"
        turn=1
        scope.launch{ delay(420); launchBotTurn() }
    }

    Surface(color=ArcadeTokens.Bg, modifier=Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(12.dp)){
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick=onBack, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.ArrowBack,"back") }
                Text("CAPSA BANTING", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Black)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp), verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.Surface).padding(horizontal=12.dp, vertical=8.dp)){ Text("Chips $chips", fontWeight=FontWeight.Bold) }
                    IconButton(onClick={showHelp=true}, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.HelpOutline,"help") }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(10.dp)){
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.PrimaryContainer).padding(10.dp)){
                    Text("Big Two 4 pemain — 3♦ mulai, habiskan 13 kartu duluan. Single/Pair/Triple/Straight/Flush/FullHouse/Four/StraightFlush. Harus kalahkan meja, 3 pass → meja clear.", style=MaterialTheme.typography.labelMedium)
                }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(
                    when{ msg.startsWith("WIN") -> ArcadeTokens.AccentContainer
                        msg.contains("PASS") -> ArcadeTokens.SurfaceAlt
                        else -> ArcadeTokens.Surface }
                ).padding(10.dp), contentAlignment=Alignment.Center){
                    Text(msg, fontWeight=FontWeight.Bold, fontSize=12.sp, color=ArcadeTokens.Text)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.Surface).padding(horizontal=12.dp, vertical=8.dp)){ Text("Meja: ${table?.cards?.joinToString(" "){it.label()} ?: "— kosong"}", fontWeight=FontWeight.Bold, fontSize=11.sp) }
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if(phase=="win") ArcadeTokens.Accent else if(turn==0) ArcadeTokens.Primary else ArcadeTokens.BgMuted).padding(horizontal=10.dp, vertical=6.dp)){ Text(if(phase=="win") "SELESAI" else if(turn==0) "▶ Giliran Kamu" else "⏳ Bot $turn", color=if(turn==0||phase=="win") Color.White else ArcadeTokens.Text, fontWeight=FontWeight.Bold, fontSize=11.sp) }
                }
                // bots row
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    for(i in 1..3){
                        val isTurn=turn==i && phase=="play"
                        val cnt=hands.getOrNull(i)?.size ?: 13
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.Surface).border(if(isTurn) 2.dp else 1.dp, if(isTurn) ArcadeTokens.Primary else ArcadeTokens.BgMuted, RoundedCornerShape(12.dp)).padding(8.dp), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Text("Bot $i ${if(isTurn)"▶" else ""}", fontWeight=FontWeight.Bold, fontSize=11.sp, color=if(isTurn) ArcadeTokens.PrimaryDark else ArcadeTokens.Text)
                            Text("$cnt kartu", fontSize=11.sp, color=ArcadeTokens.TextMuted)
                            Row(horizontalArrangement=Arrangement.spacedBy(2.dp)){
                                repeat(minOf(cnt,7)){
                                    Box(Modifier.size(width=14.dp,height=20.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF2D3436)))
                                }
                                if(cnt>7) Text("+${cnt-7}", fontSize=9.sp, color=ArcadeTokens.TextFaint)
                            }
                            if(lastPlayer==i && table!=null) Text(table!!.cards.joinToString(" "){it.label()}, fontSize=9.sp, color=ArcadeTokens.TextMuted, maxLines=1)
                        }
                    }
                }
                // table center
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0F3D2C)).padding(12.dp), contentAlignment=Alignment.Center){
                    Column(horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Text("MEJA ${if(table==null)"(kosong — bebas buka)" else "— kalahkan ini"}", color=Color.White.copy(alpha=0.8f), fontSize=10.sp, fontWeight=FontWeight.Bold)
                        Row(horizontalArrangement=Arrangement.spacedBy(6.dp), verticalAlignment=Alignment.CenterVertically){
                            if(table==null){
                                Text("—", color=Color.White.copy(alpha=0.6f), fontWeight=FontWeight.Bold)
                            } else {
                                for(c in table!!.cards){
                                    Box(Modifier.size(width=44.dp,height=58.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).border(1.dp, ArcadeTokens.Primary, RoundedCornerShape(8.dp)), contentAlignment=Alignment.Center){
                                        Text(c.label(), color=c.col, fontWeight=FontWeight.Black, fontSize=11.sp)
                                    }
                                }
                            }
                        }
                        if(table!=null) Text(when(table!!.type){1->"Single";2->"Pair";3->"Triple";9->"Four";10->"DoublePair";4->"Straight";5->"Flush";6->"Full House";7->"Four + kicker";8->"Straight Flush";else->""}, color=Color.White.copy(alpha=0.7f), fontSize=10.sp)
                    }
                }
                // your hand
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ArcadeTokens.Surface).border(if(turn==0) 2.dp else 1.dp, if(turn==0) ArcadeTokens.Primary else ArcadeTokens.BgMuted, RoundedCornerShape(14.dp)).padding(10.dp), verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                        Text("Kartu Kamu (${hands.getOrNull(0)?.size ?: 0}) ${if(selected.isNotEmpty())"— ${selected.size} dipilih" else ""}", fontWeight=FontWeight.Bold, fontSize=12.sp)
                        if(selected.isNotEmpty()) TextButton(onClick={selected=emptySet()}){ Text("Clear") }
                    }
                    val hand=hands.getOrNull(0) ?: listOf()
                    if(hand.isEmpty() && phase!="idle"){
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment=Alignment.Center){ Text("Habis! 🎉", fontWeight=FontWeight.Black, color=ArcadeTokens.Primary) }
                    } else {
                        Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                            // real row with indices
                            val indices=hand.indices.toList()
                            for(chunk in indices.chunked(6)){
                                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                                    for(i in chunk){
                                        val c=hand[i]
                                        val sel=i in selected
                                        Box(Modifier.size(width=52.dp,height=70.dp).clip(RoundedCornerShape(10.dp)).background(if(sel) ArcadeTokens.PrimaryContainer else Color.White).border(if(sel) 2.dp else 1.dp, if(sel) ArcadeTokens.Primary else ArcadeTokens.BgMuted, RoundedCornerShape(10.dp)).clickable(enabled=phase=="play"&&turn==0&&!botThinking){ selected= if(sel) selected - i else selected + i }, contentAlignment=Alignment.Center){
                                            Text(c.label(), color=c.col, fontWeight=FontWeight.Black, fontSize=13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if(hand.isNotEmpty() && table!=null){
                        val picked=selected.map{ hand[it] }
                        val cl=picked.let{ try{classify(it)}catch(_:Exception){null} }
                        Text(
                            when{
                                selected.isEmpty()-> "Pilih kartu lalu Play — harus kalahkan meja"
                                cl==null-> "Kombinasi tidak valid (Single/Pair/Triple/Four/DoublePair/5-kartu)"
                                table!=null && !beats(cl, table!!)-> "Tidak mengalahkan meja"
                                else-> "Siap: ${cl.cards.joinToString(" "){it.label()}} — ${when(cl.type){1->"Single";2->"Pair";3->"Triple";9->"Four";10->"DoublePair";4->"Straight";5->"Flush";6->"Full House";7->"Four+1";8->"Straight Flush";else->""}} ✓"
                            }, fontSize=11.sp, color=if(cl!=null && (table==null || beats(cl, table!!))) ArcadeTokens.PrimaryDark else ArcadeTokens.TextMuted
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            when(phase){
                "idle","win"->{
                    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        FilterChip(selected=difficulty==0, onClick={difficulty=0}, label={Text("Easy")}, modifier=Modifier.weight(1f).height(40.dp), colors=FilterChipDefaults.filterChipColors(selectedContainerColor=ArcadeTokens.Primary, selectedLabelColor=Color.White))
                        FilterChip(selected=difficulty==1, onClick={difficulty=1}, label={Text("Medium")}, modifier=Modifier.weight(1f).height(40.dp), colors=FilterChipDefaults.filterChipColors(selectedContainerColor=ArcadeTokens.Primary, selectedLabelColor=Color.White))
                        FilterChip(selected=difficulty==2, onClick={difficulty=2}, label={Text("Hard")}, modifier=Modifier.weight(1f).height(40.dp), colors=FilterChipDefaults.filterChipColors(selectedContainerColor=ArcadeTokens.Primary, selectedLabelColor=Color.White))
                    }
                    Row(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Button(onClick={ deal() }, modifier=Modifier.weight(1f).height(48.dp)){ Text(if(phase=="win") "New Game" else "Deal") }
                        if(phase=="win") OutlinedButton(onClick={ hands=listOf(); table=null; phase="idle"; msg="Tap Deal — Capsa Banting vs 3 bots"; turn=0; selected=emptySet() }, modifier=Modifier.height(48.dp)){ Text("Clear") }
                    }
                }
                else->{
                    Row(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        OutlinedButton(onClick={ playerPass() }, enabled=turn==0 && !botThinking && table!=null, modifier=Modifier.weight(1f).height(48.dp)){ Text("Pass") }
                        Button(onClick={ playerPlay() }, enabled=turn==0 && !botThinking && selected.isNotEmpty(), modifier=Modifier.weight(1f).height(48.dp)){ Text("Play ${if(selected.isNotEmpty())"(${selected.size})" else ""}") }
                        val hand0=hands.getOrNull(0) ?: listOf()
                        if(selected.isNotEmpty() && selected.size<4 && hand0.isNotEmpty() && hand0.size>=selected.maxOf{it}+1){
                            val picked=selected.map{ hand0[it] }
                            val cl=picked.let{ try{classify(it)}catch(_:Exception){null} }
                            val canChain = cl!=null && (table==null || beats(cl, table!!))
                            if(canChain) OutlinedButton(onClick={ playerChain() }, modifier=Modifier.height(48.dp)){ Text("Chain") }
                        }
                    }
                    if(botThinking) LinearProgressIndicator(modifier=Modifier.fillMaxWidth().padding(top=6.dp))
                }
            }
        }
    }
    if(showHelp){
        AlertDialog(onDismissRequest={showHelp=false}, title={Text("Capsa Banting")}, text={
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                Text("Buang kartu searah jarum jam. 3♦ mulai. Difficulty: Easy (blunder 45%) / Medium (18%) / Hard (8% + pilih Four/DoublePair).", style=MaterialTheme.typography.bodyMedium)
                Text("Kombinasi: Single(1), Pair(2), Triple(3), Four(4), DoublePair(2+2), Straight(5), Flush(5), Full House(3+2), Four(4+1), Straight Flush(5).", fontSize=12.sp)
                Text("Harus lawan jumlah kartu sama & lebih tinggi. 5-kartu: StraightFlush > Four > FullHouse > Flush > Straight.", fontSize=12.sp, color=ArcadeTokens.TextMuted)
                Text("Gak bisa kalahkan → Pass. 3 Pass → meja clear, bebas buka. Habiskan 13 kartu duluan menang +chips.", fontSize=12.sp, color=ArcadeTokens.TextMuted)
                Text("Rank: 3 < 4 < 5 < 6 < 7 < 8 < 9 < 10 < J < Q < K < A < 2. Suit: ♦ < ♣ < ♥ < ♠.", fontSize=11.sp, color=ArcadeTokens.TextMuted)
            }
        }, confirmButton={ TextButton(onClick={showHelp=false}){Text("Got it")}} )
    }
}
