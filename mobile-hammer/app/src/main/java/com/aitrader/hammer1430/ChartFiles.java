package com.aitrader.hammer1430;

import java.io.*;
import java.nio.file.*;

/** Publish completed history in one replacement, preserving the old file on write failure. */
final class ChartFiles {
    static void write(File destination,byte[] bytes)throws IOException {
        File directory=destination.getParentFile();if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法创建行情缓存目录");
        File temporary=File.createTempFile("chart-",".tmp",directory);
        try {
            try(FileOutputStream out=new FileOutputStream(temporary)){out.write(bytes);out.getFD().sync();}
            try{Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING);}
        }finally{temporary.delete();}
    }
}
