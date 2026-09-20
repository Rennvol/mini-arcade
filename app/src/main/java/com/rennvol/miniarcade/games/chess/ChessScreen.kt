package com.rennvol.miniarcade.games.chess

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
import androidx.compose.material.icons.filled.Undo
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
import kotlin.math.abs
import kotlin.random.Random

// ponytail: no en-passant, simplified castling, no 50-move/threefold — add when need FIDE-complete
private enum class PType{ P,N,B,R,Q,K }
private data class Piece(val type:PType,val white:Boolean)
private data class Move(val fr:Int,val fc:Int,val tr:Int,val tc:Int,val promo:PType?=null)
private data class Rights(var wk:Boolean=true,var wq:Boolean=true,var bk:Boolean=true,var bq:Boolean=true)

private fun initialBoard():Array<Array<Piece?>>{
    val b=Array(8){Array<Piece?>(8){null} }
    // black back rank 0
    b[0][0]=Piece(PType.R,false); b[0][1]=Piece(PType.N,false); b[0][2]=Piece(PType.B,false); b[0][3]=Piece(PType.Q,false)
    b[0][4]=Piece(PType.K,false); b[0][5]=Piece(PType.B,false); b[0][6]=Piece(PType.N,false); b[0][7]=Piece(PType.R,false)
    for(c in 0..7) b[1][c]=Piece(PType.P,false)
    for(c in 0..7) b[6][c]=Piece(PType.P,true)
    b[7][0]=Piece(PType.R,true); b[7][1]=Piece(PType.N,true); b[7][2]=Piece(PType.B,true); b[7][3]=Piece(PType.Q,true)
    b[7][4]=Piece(PType.K,true); b[7][5]=Piece(PType.B,true); b[7][6]=Piece(PType.N,true); b[7][7]=Piece(PType.R,true)
    return b
}
private fun copyBoard(b:Array<Array<Piece?>>):Array<Array<Piece?>> = Array(8){ r-> Array(8){ c-> b[r][c] } }

private fun inB(r:Int,c:Int)= r in 0..7 && c in 0..7
private fun findKing(b:Array<Array<Piece?>>,white:Boolean):Pair<Int,Int>?{
    for(r in 0..7) for(c in 0..7) if(b[r][c]?.type==PType.K && b[r][c]?.white==white) return r to c
    return null
}
private fun pieceAttacks(b:Array<Array<Piece?>>,r:Int,c:Int,tr:Int,tc:Int):Boolean{
    val p=b[r][c]?:return false
    when(p.type){
        PType.P->{ val dir= if(p.white) -1 else 1; return tr==r+dir && abs(tc-c)==1 }
        PType.N->{ return (abs(tr-r)==2 && abs(tc-c)==1) || (abs(tr-r)==1 && abs(tc-c)==2) }
        PType.B->{ if(abs(tr-r)!=abs(tc-c)) return false; val sr=if(tr>r)1 else -1; val sc=if(tc>c)1 else -1; var cr=r+sr; var cc=c+sc; while(cr!=tr){ if(b[cr][cc]!=null) return false; cr+=sr; cc+=sc }; return true }
        PType.R->{ if(tr!=r && tc!=c) return false; if(tr==r){ val sc=if(tc>c)1 else -1; var cc=c+sc; while(cc!=tc){ if(b[r][cc]!=null) return false; cc+=sc } } else { val sr=if(tr>r)1 else -1; var cr=r+sr; while(cr!=tr){ if(b[cr][c]!=null) return false; cr+=sr } }; return true }
        PType.Q->{ return pieceAttacks(copyWith(b,r,c,Piece(PType.B,p.white)),r,c,tr,tc) || pieceAttacks(copyWith(b,r,c,Piece(PType.R,p.white)),r,c,tr,tc) }
        PType.K->{ return abs(tr-r)<=1 && abs(tc-c)<=1 }
    }
}
private fun copyWith(b:Array<Array<Piece?>>,r:Int,c:Int,p:Piece):Array<Array<Piece?>>{ val nb=copyBoard(b); nb[r][c]=p; return nb }

private fun isAttacked(b:Array<Array<Piece?>>,tr:Int,tc:Int,byWhite:Boolean):Boolean{
    for(r in 0..7) for(c in 0..7){ val p=b[r][c]; if(p!=null && p.white==byWhite){ if(pieceAttacks(b,r,c,tr,tc)) return true } }
    return false
}
private fun isInCheck(b:Array<Array<Piece?>>,white:Boolean):Boolean{
    val k=findKing(b,white)?:return false; return isAttacked(b,k.first,k.second,!white)
}

private fun pseudoMoves(b:Array<Array<Piece?>>,r:Int,c:Int,rights:Rights):List<Move>{
    val p=b[r][c]?:return emptyList(); val out=mutableListOf<Move>()
    when(p.type){
        PType.P->{
            val dir=if(p.white) -1 else 1; val start=if(p.white)6 else 1; val promoRow=if(p.white)0 else 7
            if(inB(r+dir,c) && b[r+dir][c]==null){
                if(r+dir==promoRow) out.add(Move(r,c,r+dir,c,PType.Q)) else out.add(Move(r,c,r+dir,c))
                if(r==start && b[r+dir*2][c]==null) out.add(Move(r,c,r+dir*2,c))
            }
            for(dc in listOf(-1,1)){ if(inB(r+dir,c+dc) && b[r+dir][c+dc]!=null && b[r+dir][c+dc]!!.white!=p.white){
                if(r+dir==promoRow) out.add(Move(r,c,r+dir,c+dc,PType.Q)) else out.add(Move(r,c,r+dir,c+dc)) } }
        }
        PType.N->{ val d=listOf(-2 to -1,-2 to 1,-1 to -2,-1 to 2,1 to -2,1 to 2,2 to -1,2 to 1); for((dr,dc) in d){ val nr=r+dr; val nc=c+dc; if(inB(nr,nc) && (b[nr][nc]==null || b[nr][nc]!!.white!=p.white)) out.add(Move(r,c,nr,nc)) } }
        PType.B, PType.R, PType.Q->{
            val dirs=when(p.type){ PType.B-> listOf(-1 to -1,-1 to 1,1 to -1,1 to 1); PType.R-> listOf(-1 to 0,1 to 0,0 to -1,0 to 1); else-> listOf(-1 to -1,-1 to 0,-1 to 1,0 to -1,0 to 1,1 to -1,1 to 0,1 to 1) }
            for((dr,dc) in dirs){ var nr=r+dr; var nc=c+dc; while(inB(nr,nc)){ if(b[nr][nc]==null) out.add(Move(r,c,nr,nc)) else { if(b[nr][nc]!!.white!=p.white) out.add(Move(r,c,nr,nc)); break }; nr+=dr; nc+=dc } }
        }
        PType.K->{
            for(dr in -1..1) for(dc in -1..1) if(dr!=0||dc!=0){ val nr=r+dr; val nc=c+dc; if(inB(nr,nc) && (b[nr][nc]==null || b[nr][nc]!!.white!=p.white)) out.add(Move(r,c,nr,nc)) }
            // castling simplified
            if(p.white && r==7 && c==4 && !isInCheck(b,true)){
                if(rights.wk && b[7][5]==null && b[7][6]==null && b[7][7]?.type==PType.R && !isAttacked(b,7,5,false) && !isAttacked(b,7,6,false)) out.add(Move(r,c,7,6))
                if(rights.wq && b[7][1]==null && b[7][2]==null && b[7][3]==null && b[7][0]?.type==PType.R && !isAttacked(b,7,3,false) && !isAttacked(b,7,2,false)) out.add(Move(r,c,7,2))
            }
            if(!p.white && r==0 && c==4 && !isInCheck(b,false)){
                if(rights.bk && b[0][5]==null && b[0][6]==null && b[0][7]?.type==PType.R && !isAttacked(b,0,5,true) && !isAttacked(b,0,6,true)) out.add(Move(r,c,0,6))
                if(rights.bq && b[0][1]==null && b[0][2]==null && b[0][3]==null && b[0][0]?.type==PType.R && !isAttacked(b,0,3,true) && !isAttacked(b,0,2,true)) out.add(Move(r,c,0,2))
            }
        }
    }
    return out
}
private fun applyMove(b:Array<Array<Piece?>>,m:Move,rights:Rights):Array<Array<Piece?>>{
    val nb=copyBoard(b); val p=nb[m.fr][m.fc]?:return nb; nb[m.fr][m.fc]=null
    // castling rook move
    if(p.type==PType.K && abs(m.tc - m.fc)==2){
        if(m.tc==6){ // king side
            nb[m.fr][5]=nb[m.fr][7]; nb[m.fr][7]=null
        } else { // queen side 2
            nb[m.fr][3]=nb[m.fr][0]; nb[m.fr][0]=null
        }
    }
    val promo=m.promo
    nb[m.tr][m.tc]= if(promo!=null) Piece(promo,p.white) else p
    // update rights
    if(p.type==PType.K){ if(p.white){ rights.wk=false; rights.wq=false } else { rights.bk=false; rights.bq=false } }
    if(p.type==PType.R){
        if(p.white && m.fr==7 && m.fc==0) rights.wq=false
        if(p.white && m.fr==7 && m.fc==7) rights.wk=false
        if(!p.white && m.fr==0 && m.fc==0) rights.bq=false
        if(!p.white && m.fr==0 && m.fc==7) rights.bk=false
    }
    // if rook captured, lose rights
    val cap=b[m.tr][m.tc]
    if(cap?.type==PType.R){
        if(m.tr==7 && m.tc==0) rights.wq=false
        if(m.tr==7 && m.tc==7) rights.wk=false
        if(m.tr==0 && m.tc==0) rights.bq=false
        if(m.tr==0 && m.tc==7) rights.bk=false
    }
    return nb
}
private fun legalMoves(b:Array<Array<Piece?>>,whiteTurn:Boolean,rights:Rights):List<Move>{
    val all=mutableListOf<Move>()
    for(r in 0..7) for(c in 0..7){ val p=b[r][c]; if(p!=null && p.white==whiteTurn){ for(m in pseudoMoves(b,r,c,rights)){
        val rCopy=Rights(rights.wk,rights.wq,rights.bk,rights.bq)
        val nb=applyMove(copyBoard(b),m,rCopy)
        if(!isInCheck(nb,whiteTurn)) all.add(m)
    } } }
    return all
}
private fun evalBoard(b:Array<Array<Piece?>>):Int{
    var s=0
    for(r in 0..7) for(c in 0..7){ val p=b[r][c]?:continue; val v=when(p.type){PType.P->100;PType.N->320;PType.B->330;PType.R->500;PType.Q->900;PType.K->0}; val sign=if(p.white)1 else -1
        var pos=0
        // center bonus
        if(p.type==PType.N || p.type==PType.B){ if(r in 2..5 && c in 2..5) pos+=10; if(r in 3..4 && c in 3..4) pos+=10 }
        if(p.type==PType.P){ pos+= if(p.white) (6 - r)*6 else (r - 1)*6 }
        if(p.type==PType.K){ // king safety: prefer back rank early, simplify
            if(p.white && r==7 && c in 3..5) pos+=8
            if(!p.white && r==0 && c in 3..5) pos+=8
        }
        s+= sign*(v+pos)
    }
    return s
}
private fun negamax(b:Array<Array<Piece?>>,depth:Int,alpha:Int,beta:Int,whiteTurn:Boolean,rights:Rights):Int{
    if(depth==0) return if(whiteTurn) evalBoard(b) else -evalBoard(b)
    val moves=legalMoves(b,whiteTurn,rights)
    if(moves.isEmpty()){
        return if(isInCheck(b,whiteTurn)) -99999 + (3-depth) else 0
    }
    var a=alpha; var best=-999999
    // move ordering: captures first
    val ordered=moves.sortedByDescending{ m-> val t=b[m.tr][m.tc]; if(t!=null) when(t.type){PType.Q->9;PType.R->5;PType.B->3;PType.N->3;PType.P->1;else->0} else 0 }
    for(m in ordered){
        val nr=Rights(rights.wk,rights.wq,rights.bk,rights.bq)
        val nb=applyMove(copyBoard(b),m,nr)
        val score= -negamax(nb,depth-1,-beta,-a,!whiteTurn,nr)
        if(score>best) best=score
        if(best>a) a=best
        if(a>=beta) break
    }
    return best
}
private fun pieceSym(p:Piece):String = when(p.type){
    PType.K-> if(p.white)"♔" else "♚"
    PType.Q-> if(p.white)"♕" else "♛"
    PType.R-> if(p.white)"♖" else "♜"
    PType.B-> if(p.white)"♗" else "♝"
    PType.N-> if(p.white)"♘" else "♞"
    PType.P-> if(p.white)"♙" else "♟"
}

@Composable
fun ChessScreen(onBack:()->Unit){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var chips by remember{ mutableIntStateOf(1000) }
    var difficulty by remember{ mutableIntStateOf(1) } // 0 mudah 1 sulit 2 gm
    var board by remember{ mutableStateOf(initialBoard()) }
    var rights by remember{ mutableStateOf(Rights()) }
    var whiteTurn by remember{ mutableStateOf(true) }
    var selected by remember{ mutableStateOf<Pair<Int,Int>?>(null) }
    var legalDest by remember{ mutableStateOf(setOf<Pair<Int,Int>>()) }
    var lastMove by remember{ mutableStateOf<Move?>(null) }
    var history by remember{ mutableStateOf(listOf<Triple<Array<Array<Piece?>>,Rights,Move?>>()) }
    var status by remember{ mutableStateOf("Pilih tingkat lawan lalu New Game") }
    var gameOver by remember{ mutableStateOf<String?>(null) }
    var botThinking by remember{ mutableStateOf(false) }
    var showHelp by remember{ mutableStateOf(false) }
    LaunchedEffect(Unit){ try{ ctx.dataStore.data.collect{ chips=it[Prefs.ARCADE_CHIPS]?:1000 } }catch(_:Exception){} }
    suspend fun save(c:Int){ try{ ctx.dataStore.edit{ it[Prefs.ARCADE_CHIPS]=c } }catch(_:Exception){} }

    fun reset(){
        board=initialBoard(); rights=Rights(); whiteTurn=true; selected=null; legalDest=emptySet(); lastMove=null; history=emptyList(); gameOver=null; botThinking=false
        status="Giliran Putih (Kamu) — tap bidak lalu tap tujuan"
    }
    fun checkGameEnd(){
        val moves=legalMoves(board,whiteTurn,rights)
        if(moves.isEmpty()){
            if(isInCheck(board,whiteTurn)){
                val winner= if(whiteTurn) "Hitam (Bot)" else "Putih (Kamu)"
                gameOver="$winner menang — Skakmat!"
                status=gameOver!!
                if(!whiteTurn){ // kamu menang (bot skakmat)
                    val win=80 + difficulty*40; chips+=win; scope.launch{ save(chips) }; status="Skakmat! Kamu menang +$win 🎉"
                } else status="Skakmat! Bot menang 😭"
            } else {
                gameOver="Remis — Stalemate"
                status=gameOver!! + " (seri)"
            }
        } else {
            status= (if(whiteTurn) "Giliran Kamu (Putih)" else "Giliran Bot (Hitam)") + if(isInCheck(board,whiteTurn)) " — SKAK!" else ""
        }
    }
    fun pushHistory(m:Move?){
        history = history + Triple(copyBoard(board), Rights(rights.wk,rights.wq,rights.bk,rights.bq), lastMove)
        if(history.size>80) history=history.takeLast(80)
    }
    fun doMove(m:Move){
        pushHistory(m); board=applyMove(board,m,rights); lastMove=m; whiteTurn=!whiteTurn; selected=null; legalDest=emptySet(); checkGameEnd()
    }
    fun botMove(){
        if(gameOver!=null || whiteTurn) return
        scope.launch{
            botThinking=true; delay(450)
            val depth=when(difficulty){0->1;1->2; else->3}
            val moves=legalMoves(board,false,rights)
            if(moves.isEmpty()){ botThinking=false; checkGameEnd(); return@launch }
            // easy: 35% random blunder
            val chosen:Move = if(difficulty==0 && Random.nextInt(100)<35){
                moves.random()
            } else {
                var best:Move=moves[0]; var bestScore= -9999999
                // order captures first for search
                val ordered=moves.sortedByDescending{ board[it.tr][it.tc]!=null }
                for(m in ordered){
                    val nr=Rights(rights.wk,rights.wq,rights.bk,rights.bq)
                    val nb=applyMove(copyBoard(board),m,nr)
                    val score= -negamax(nb,depth-1,-999999,999999,true,nr)
                    // gm adds tiny random to avoid repetition
                    val jitter= if(difficulty==2) Random.nextInt(-6,7) else 0
                    val s=score + jitter
                    if(s>bestScore){ bestScore=s; best=m }
                }
                best
            }
            doMove(chosen)
            botThinking=false
            // if still bot turn? no, only one side bot
        }
    }
    LaunchedEffect(whiteTurn, gameOver){
        if(!whiteTurn && gameOver==null) botMove()
    }

    Surface(color=ArcadeTokens.Bg, modifier=Modifier.fillMaxSize()){
        Column(Modifier.fillMaxSize().padding(12.dp)){
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick=onBack, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.ArrowBack,"back") }
                Text("CATUR", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Black)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp), verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.Surface).padding(horizontal=10.dp, vertical=6.dp)){ Text("Chips $chips", fontWeight=FontWeight.Bold, fontSize=12.sp) }
                    IconButton(onClick={showHelp=true}, modifier=Modifier.size(44.dp)){ Icon(Icons.Filled.HelpOutline,"help") }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(10.dp)){
                // difficulty
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArcadeTokens.Surface).padding(10.dp)){
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text("Tingkat Bot", fontWeight=FontWeight.Bold, fontSize=12.sp)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.fillMaxWidth()){
                            val labels=listOf("Mudah","Sulit","Grandmaster"); val descs=listOf("Depth 1 • blunder 35%","Depth 2 • taktis","Depth 3 • +posisi")
                            for(i in 0..2){
                                val sel=i==difficulty
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if(sel) ArcadeTokens.Primary else ArcadeTokens.BgMuted).border(if(sel) 2.dp else 0.dp, if(sel) ArcadeTokens.PrimaryDark else Color.Transparent, RoundedCornerShape(10.dp)).clickable{ difficulty=i; status="Level: ${labels[i]} — tap New Game" }.padding(8.dp), horizontalAlignment=Alignment.CenterHorizontally){
                                    Text(labels[i], fontWeight=FontWeight.Black, fontSize=11.sp, color=if(sel) Color.White else ArcadeTokens.Text, textAlign=TextAlign.Center)
                                    Text(descs[i], fontSize=9.sp, color=if(sel) Color.White.copy(alpha=0.9f) else ArcadeTokens.TextMuted, textAlign=TextAlign.Center, lineHeight=10.sp)
                                }
                            }
                        }
                        Text(when(difficulty){0->"Mudah: bot kadang blunder, cocok belajar.";1->"Sulit: lihat 2 langkah ke depan.";else->"Grandmaster: 3 langkah + posisi, susah!"} , fontSize=11.sp, color=ArcadeTokens.TextMuted)
                    }
                }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(when{gameOver?.contains("Kamu menang") == true -> ArcadeTokens.AccentContainer; gameOver!=null -> ArcadeTokens.DangerContainer; isInCheck(board,whiteTurn) -> ArcadeTokens.DangerContainer; whiteTurn -> ArcadeTokens.PrimaryContainer; else-> ArcadeTokens.SurfaceAlt } ).padding(10.dp), contentAlignment=Alignment.Center){
                    Text(status, fontWeight=FontWeight.Bold, fontSize=12.sp, color=ArcadeTokens.Text, textAlign=TextAlign.Center)
                }
                if(botThinking) LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
                // board
                BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF2D3436)).padding(8.dp), contentAlignment=Alignment.Center){
                    val size=minOf(maxWidth, 360.dp)
                    Column(Modifier.size(size).clip(RoundedCornerShape(10.dp)).border(2.dp, Color.White.copy(alpha=0.15f), RoundedCornerShape(10.dp))){
                        for(r in 0..7){
                            Row(Modifier.weight(1f)){
                                for(c in 0..7){
                                    val isLight=(r+c)%2==0
                                    val isSel=selected== (r to c)
                                    val isDest=legalDest.contains(r to c)
                                    val isLast= lastMove!=null && ((lastMove!!.fr==r && lastMove!!.fc==c) || (lastMove!!.tr==r && lastMove!!.tc==c))
                                    val bg=when{isSel-> Color(0xFFF1C40F); isLast-> if(isLight) Color(0xFFB8E986) else Color(0xFF7AC74F); isLight-> Color(0xFFEEEED2); else-> Color(0xFF769656)}
                                    val piece=board[r][c]
                                    val isWhiteTurnPiece= piece!=null && piece.white==whiteTurn && gameOver==null
                                    Box(Modifier.weight(1f).fillMaxHeight().background(bg).clickable(enabled=gameOver==null){
                                        if(selected==null){
                                            if(piece!=null && piece.white==whiteTurn){
                                                selected=r to c
                                                val ms=pseudoMoves(board,r,c,rights).filter{ m->
                                                    val rc=Rights(rights.wk,rights.wq,rights.bk,rights.bq)
                                                    val nb=applyMove(copyBoard(board),m,rc)
                                                    !isInCheck(nb,whiteTurn)
                                                }.map{ it.tr to it.tc }.toSet()
                                                legalDest=ms
                                            }
                                        } else {
                                            val sel=selected!!
                                            if(sel.first==r && sel.second==c){ selected=null; legalDest=emptySet() }
                                            else if(legalDest.contains(r to c)){
                                                // find move
                                                val ms=pseudoMoves(board,sel.first,sel.second,rights).filter{ it.tr==r && it.tc==c }
                                                // filter legal (already)
                                                val legalMs=ms.filter{ m->
                                                    val rc=Rights(rights.wk,rights.wq,rights.bk,rights.bq)
                                                    val nb=applyMove(copyBoard(board),m,rc)
                                                    !isInCheck(nb,whiteTurn)
                                                }
                                                if(legalMs.isNotEmpty()) doMove(legalMs[0]) else { selected=null; legalDest=emptySet() }
                                            } else {
                                                // switch selection if own piece
                                                if(piece!=null && piece.white==whiteTurn){
                                                    selected=r to c
                                                    val ms=pseudoMoves(board,r,c,rights).filter{ m->
                                                        val rc=Rights(rights.wk,rights.wq,rights.bk,rights.bq)
                                                        val nb=applyMove(copyBoard(board),m,rc)
                                                        !isInCheck(nb,whiteTurn)
                                                    }.map{ it.tr to it.tc }.toSet()
                                                    legalDest=ms
                                                } else { selected=null; legalDest=emptySet() }
                                            }
                                        }
                                    }, contentAlignment=Alignment.Center){
                                        if(piece!=null){
                                            Text(pieceSym(piece), fontSize=22.sp, fontWeight=FontWeight.Black, color=if(piece.white) Color.White else Color(0xFF1A1A1A), modifier=Modifier.background(if(piece.white) Color(0xFF34495E).copy(alpha=0.9f) else Color.Transparent, RoundedCornerShape(6.dp)).padding(horizontal=2.dp))
                                        }
                                        if(isDest){
                                            Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(if(board[r][c]!=null) Color(0xFFE74C3C).copy(alpha=0.85f) else Color(0xFF2C3E50).copy(alpha=0.55f)))
                                        }
                                        if(r==7) Text("${'a'+c}", fontSize=7.sp, color=if(isLight) Color(0xFF769656) else Color(0xFFEEEED2), modifier=Modifier.align(Alignment.BottomEnd).padding(1.dp))
                                        if(c==0) Text("${8-r}", fontSize=7.sp, color=if(isLight) Color(0xFF769656) else Color(0xFFEEEED2), modifier=Modifier.align(Alignment.TopStart).padding(1.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                Text("Tap bidak Putihmu → titik hitam = langkah. Merah = makan. Kuning = dipilih. Kamu Putih, Bot Hitam.", fontSize=11.sp, color=ArcadeTokens.TextMuted, textAlign=TextAlign.Center, modifier=Modifier.fillMaxWidth())
                // captured bar
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ArcadeTokens.Surface).padding(8.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically){
                    Column{ Text("Putih (Kamu)", fontSize=10.sp, fontWeight=FontWeight.Bold, color=ArcadeTokens.TextMuted); Text(if(whiteTurn) "▶ giliran" else "menunggu", fontSize=11.sp, fontWeight=FontWeight.Black, color=if(whiteTurn) ArcadeTokens.Primary else ArcadeTokens.TextFaint) }
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(ArcadeTokens.BgMuted).padding(horizontal=10.dp, vertical=6.dp)){ Text(when(difficulty){0->"Mudah";1->"Sulit";else->"GM"}, fontWeight=FontWeight.Black, fontSize=11.sp) }
                    Column(horizontalAlignment=Alignment.End){ Text("Hitam (Bot)", fontSize=10.sp, fontWeight=FontWeight.Bold, color=ArcadeTokens.TextMuted); Text(if(!whiteTurn) "▶ berpikir" else "menunggu", fontSize=11.sp, fontWeight=FontWeight.Black, color=if(!whiteTurn) ArcadeTokens.Danger else ArcadeTokens.TextFaint) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(onClick={ reset() }, modifier=Modifier.weight(1f).height(48.dp)){ Text("New Game") }
                OutlinedButton(onClick={
                    if(history.isNotEmpty()){
                        // undo last 2 ply (player+bot) or 1
                        val toUndo= if(history.size>=2 && !whiteTurn) 1 else minOf(2, history.size)
                        repeat(toUndo){
                            val last=history.lastOrNull() ?: return@repeat
                            history=history.dropLast(1)
                            board=last.first; rights=last.second; lastMove=last.third
                        }
                        whiteTurn=true; gameOver=null; selected=null; legalDest=emptySet(); status="Undo — giliran Kamu"
                    }
                }, modifier=Modifier.height(48.dp), enabled=history.isNotEmpty() && gameOver==null){
                    Icon(Icons.Filled.Undo,null, modifier=Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Undo")
                }
            }
        }
    }
    if(showHelp){
        AlertDialog(onDismissRequest={showHelp=false}, title={Text("Cara Main Catur")}, text={
            Column(verticalArrangement=Arrangement.spacedBy(6.dp), modifier=Modifier.verticalScroll(rememberScrollState())){
                Text("Kamu Putih di bawah, Bot Hitam di atas. Putih jalan duluan.", fontWeight=FontWeight.Bold, fontSize=13.sp)
                Text("Tap bidakmu → titik = langkah sah. Tap tujuan untuk jalan. Tap lagi bidak lain untuk ganti pilihan.", fontSize=12.sp)
                Text("Bidak: Pion maju 1 (2 dari awal), makan diagonal • Kuda L • Gajah diagonal • Benteng lurus • Menteri semua arah • Raja 1 kotak + rokade.", fontSize=12.sp)
                Text("Rokade: Raja 2 ke samping jika belum gerak, tidak skak, jalur kosong & tidak diserang.", fontSize=11.sp, color=ArcadeTokens.TextMuted)
                Text("Skak = Raja diserang. Wajib keluar dari skak. Skakmat = tidak ada langkah sah saat skak → kalah. Stalemate = tidak ada langkah tapi tidak skak → remis.", fontSize=12.sp)
                Text("Tingkat: Mudah (sering blunder) → Sulit (2 langkah) → Grandmaster (3 langkah + posisi). Menang +80/120/160 chips.", fontSize=11.sp, fontWeight=FontWeight.Bold)
                Text("Pion promosi otomatis jadi Menteri (♕). En passant & 50 langkah belum ada.", fontSize=10.sp, color=ArcadeTokens.TextFaint)
            }
        }, confirmButton={ TextButton(onClick={showHelp=false}){Text("Got it")} })
    }
}
