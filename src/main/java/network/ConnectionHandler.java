package network;

import io.netty.channel.*;
import message.*;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;

public class ConnectionHandler extends SimpleChannelInboundHandler<Message> {

    private BlockingQueue<Message> inQueue;
    private ChannelHandlerContext ctx;
    private int partSize = (int) (0.5 * 1024 * 1024);
    private enum State {
        IDLE, DWN, UPL, LIST
    }
    private State state;
    private String filepath;
    private String rootFolder;

    public ConnectionHandler(BlockingQueue<Message> inQueue, String rootFolder) {
        this.inQueue = inQueue;
        this.rootFolder = rootFolder;
        this.state = State.IDLE;
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Message msg) {
        System.out.println("Otrzymano:");
        System.out.println(msg.toString());
        if (msg.getType() == Message.Type.SETTINGS)
            partSize = ((MsgSettings) msg).getPartSize();
        else if (msg.getType() == Message.Type.EXIT)
            ctx.close();
        else if (msg.getType() == Message.Type.CHUNK) {
            if (state == State.DWN)
                fileAppend(filepath, ((MsgFileChunk) msg).getData(), ((MsgFileChunk) msg).getPart() * partSize);
            else if (state == State.LIST) {
                System.out.println("Appenduje list");
                fileAppend("filelist.list", ((MsgFileChunk) msg).getData(), ((MsgFileChunk) msg).getPart() * partSize);
            }
        } else {
            if (msg.getType() == Message.Type.OK)
                state = State.IDLE;
            try {
                inQueue.put(msg);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }

    public void send(Message m) {
        ctx.writeAndFlush(m);
    }

    public void getFile(MsgGetFile msg) {
        filepath = msg.getPath();
        filepath = Paths.get(rootFolder, filepath).toString();
        state = State.DWN;
        ctx.writeAndFlush(msg);
    }

    public void getFileVer(MsgGetFileVer msg) {
        filepath = msg.getPath();
        ctx.writeAndFlush(msg);
    }

    public void getList(MsgList msg) {
        state = State.LIST;
        ctx.writeAndFlush(msg);
    }

    public void sendFile(MsgAddFile msg) {
        Path path = Paths.get(rootFolder, msg.getPath());
        filepath = path.toString();
        state = State.UPL;
        ctx.writeAndFlush(msg);

        try {
            System.out.println(filepath);
            RandomAccessFile file = new RandomAccessFile(filepath, "rw");
            int parts = (int) (file.length() + partSize - 1) / partSize;
            System.out.println(file.length() + " " + partSize + " " + parts);
            for (int currPart = 0; currPart < parts; currPart++) {
                byte[] data = getPart(file, currPart);
                ctx.writeAndFlush(new MsgFileChunk(data, currPart));
            }
            file.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void fileAppend(String pathstr, byte[] buff, int pos) {
        System.out.println("Appenduje plik: " + pathstr + " pos: " + pos);
        try {
            if (state != State.LIST && pos == 0) {
                Path path = Paths.get(pathstr);
                try {
                    if (path.toFile().exists())
                        Files.delete(path);
                    File parent = path.toFile().getParentFile();
                    if (!parent.exists() && !parent.mkdirs())
                        throw new IllegalStateException("Couldn't create dir: " + parent);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            Path path = Paths.get(pathstr);
            RandomAccessFile file = new RandomAccessFile(path.toAbsolutePath().toString(), "rw");
            file.seek(pos);
            file.write(buff);
            file.close();
        } catch (Exception e) {
            System.out.println(e.toString());
        }
    }

    public byte[] getPart(RandomAccessFile file, int part) {
        try {
            System.out.println(part + " " + partSize + " " + file.length());
            if (part * partSize >= file.length()) {
                throw new IllegalArgumentException("Argument part jest zbyt duzy.");
            }
            file.seek(part * partSize);
            byte[] buff = new byte[partSize];
            int n = file.read(buff);
            byte[] slice = Arrays.copyOfRange(buff, 0, n);
            return slice;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void disconnect() {
        System.out.println("Zamykam połączenie...");
        ChannelFuture f = ctx.writeAndFlush(new MsgExit());
        f.addListener(ChannelFutureListener.CLOSE);
    }

}
