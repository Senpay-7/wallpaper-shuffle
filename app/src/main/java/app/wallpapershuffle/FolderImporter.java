package app.wallpapershuffle;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Copies every photo, GIF and video of a chosen folder (with subfolders) once. */
public final class FolderImporter {
    public static final ExecutorService IO=Executors.newSingleThreadExecutor();
    public interface Progress{void update(int imported);}
    public record Result(int imported,boolean noSpace){}
    public static Result copyFolder(Context ctx,Uri tree,AtomicBoolean cancel,Progress progress){
        LibraryStore db=LibraryStore.get(ctx);int count=0;ArrayDeque<String> dirs=new ArrayDeque<>();
        try{dirs.add(DocumentsContract.getTreeDocumentId(tree));}catch(Exception e){return new Result(0,false);}
        while(!dirs.isEmpty()&&!cancel.get()){
            Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,dirs.removeFirst());
            try(Cursor c=ctx.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
                while(c!=null&&c.moveToNext()&&!cancel.get()){
                    String id=c.getString(0),mime=c.getString(1);
                    if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){dirs.add(id);continue;}
                    if(mime==null||(!mime.startsWith("image/")&&!mime.startsWith("video/")))continue;
                    try{db.importUri(DocumentsContract.buildDocumentUriUsingTree(tree,id),cancel);count++;if(progress!=null)progress.update(count);}
                    catch(LibraryStore.NoSpaceException e){return new Result(count,true);}
                    catch(Exception ignored){}
                }
            }catch(Exception ignored){}
        }
        return new Result(count,false);
    }
}
