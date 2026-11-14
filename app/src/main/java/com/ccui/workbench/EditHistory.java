package com.ccui.workbench;

import java.util.*;

/** Bounded byte snapshots preserve unknown CSB data as well as node structure. */
final class EditHistory {
    private final Deque<byte[]> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private long used;
    private static final long LIMIT=32L*1024*1024;
    void clear(){undo.clear();redo.clear();used=0;}
    void record(byte[] before,byte[] after){if(Arrays.equals(before,after))return;redo.clear();undo.addLast(before.clone());used+=before.length;while(undo.size()>1&&(undo.size()>40||used>LIMIT))used-=undo.removeFirst().length;}
    boolean canUndo(){return !undo.isEmpty();}
    boolean canRedo(){return !redo.isEmpty();}
    byte[] undo(byte[] current){if(!canUndo())return current;byte[] previous=undo.removeLast();used-=previous.length;redo.addLast(current.clone());return previous;}
    byte[] redo(byte[] current){if(!canRedo())return current;byte[] next=redo.removeLast();undo.addLast(current.clone());used+=current.length;return next;}
}
