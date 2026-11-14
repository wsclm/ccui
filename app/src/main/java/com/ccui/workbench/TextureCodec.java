package com.ccui.workbench;

import java.nio.*;
import java.io.*;
import java.util.zip.*;

/** KTX 1 ASTC 6x6 encoding using valid LDR constant-color blocks. */
final class TextureCodec {
    static byte[] encode(byte[] png)throws IOException{return astc(decode(png));}
    private static byte[] astc(byte[] rgba){ByteBuffer source=ByteBuffer.wrap(rgba).order(ByteOrder.LITTLE_ENDIAN);int w=source.getInt(36),h=source.getInt(40),size=((w+5)/6)*((h+5)/6)*16;ByteBuffer out=ByteBuffer.allocate(68+size).order(ByteOrder.LITTLE_ENDIAN);out.put(rgba,0,68);out.putInt(16,0);out.putInt(24,0);out.putInt(28,0x93b4);out.putInt(32,0x93b4);out.putInt(64,size);out.position(68);for(int y=0;y<h;y+=6)for(int x=0;x<w;x+=6){long[] sum=new long[4];int count=0;for(int yy=y;yy<Math.min(y+6,h);yy++)for(int xx=x;xx<Math.min(x+6,w);xx++){for(int c=0;c<4;c++)sum[c]+=rgba[68+(yy*w+xx)*4+c]&255;count++;}out.putLong(0xfffffffffffffdfcL);for(long value:sum)out.putShort((short)(value*257/count));}return out.array();}
    static byte[] encode(byte[] png,int width,int height)throws IOException{
        byte[] raw=decode(png);ByteBuffer header=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);int w=header.getInt(36),h=header.getInt(40);if(w==width&&h==height)return astc(raw);
        if(width<=0||height<=0||(long)width*height>16777216)throw new IOException("纹理尺寸无效");
        byte[] result=new byte[68+width*height*4];System.arraycopy(raw,0,result,0,68);ByteBuffer b=ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);b.putInt(36,width);b.putInt(40,height);b.putInt(64,width*height*4);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)System.arraycopy(raw,68+((y*h/height)*w+x*w/width)*4,result,68+(y*width+x)*4,4);return astc(result);
    }
    private static byte[] decode(byte[] png)throws IOException{
            ByteBuffer input=ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN);int w=0,h=0,depth=0,color=0,interlace=0;byte[] palette=new byte[0],alpha=new byte[0];ByteArrayOutputStream compressed=new ByteArrayOutputStream();input.position(8);
            while(input.remaining()>=12){int size=input.getInt(),tag=input.getInt();if(size<0||size>input.remaining()-4)throw new IOException("PNG 数据不完整");byte[] chunk=new byte[size];input.get(chunk);input.getInt();ByteBuffer data=ByteBuffer.wrap(chunk).order(ByteOrder.BIG_ENDIAN);
                if(tag==0x49484452){w=data.getInt();h=data.getInt();depth=data.get()&255;color=data.get()&255;data.get();data.get();interlace=data.get()&255;}
                else if(tag==0x49444154)compressed.write(chunk);else if(tag==0x504c5445)palette=chunk;else if(tag==0x74524e53)alpha=chunk;else if(tag==0x49454e44)break;
            }
            if(w<=0||h<=0||(long)w*h>16777216)throw new IOException("图片无效或超过 1600 万像素");
            if(interlace!=0||!(depth==1||depth==2||depth==4||depth==8||depth==16))throw new IOException("请使用非交错 PNG");
            int channels=switch(color){case 0,3->1;case 2->3;case 4->2;case 6->4;default->throw new IOException("PNG 颜色格式不支持");};
            int stride=(w*channels*depth+7)/8,bpp=Math.max(1,(channels*depth+7)/8);byte[] previous=new byte[stride];
            ByteBuffer out=ByteBuffer.allocate(68+w*h*4).order(ByteOrder.LITTLE_ENDIAN);
            out.put(new byte[]{(byte)171,75,84,88,32,49,49,(byte)187,13,10,26,10});
            for(int value:new int[]{0x04030201,0x1401,1,0x1908,0x1908,0x1908,w,h,0,0,1,1,0,w*h*4})out.putInt(value);
            try(DataInputStream raw=new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressed.toByteArray())))){
                for(int y=0;y<h;y++){int filter=raw.readUnsignedByte();if(filter>4)throw new IOException("PNG filter 无效");byte[] row=new byte[stride];raw.readFully(row);
                    for(int x=0;x<stride;x++){int a=x>=bpp?row[x-bpp]&255:0,b=previous[x]&255,c=x>=bpp?previous[x-bpp]&255:0,p=a+b-c,pa=Math.abs(p-a),pb=Math.abs(p-b),pc=Math.abs(p-c);int value=switch(filter){case 0->0;case 1->a;case 2->b;case 3->(a+b)/2;default->pa<=pb&&pa<=pc?a:pb<=pc?b:c;};row[x]=(byte)((row[x]&255)+value);}
                    previous=row;int max=(1<<depth)-1;
                    for(int x=0;x<w;x++){int[] s=new int[channels],v=new int[channels];for(int k=0;k<channels;k++){int bit=(x*channels+k)*depth;s[k]=depth==16?((row[bit/8]&255)<<8)|(row[bit/8+1]&255):((row[bit/8]&255)>>(8-depth-bit%8))&max;v[k]=s[k]*255/max;}
                        int r,g,b,a=255;
                        if(color==3){int idx=s[0];if(idx*3+2>=palette.length)throw new IOException("PNG 调色板无效");r=palette[idx*3]&255;g=palette[idx*3+1]&255;b=palette[idx*3+2]&255;if(idx<alpha.length)a=alpha[idx]&255;}
                        else if(color==0||color==4){r=g=b=v[0];if(color==4)a=v[1];else if(alpha.length==2&&s[0]==(((alpha[0]&255)<<8)|(alpha[1]&255)))a=0;}
                        else{r=v[0];g=v[1];b=v[2];if(color==6)a=v[3];else if(alpha.length==6){boolean transparent=true;for(int k=0;k<3;k++)transparent&=s[k]==(((alpha[k*2]&255)<<8)|(alpha[k*2+1]&255));if(transparent)a=0;}}
                        out.put((byte)r).put((byte)g).put((byte)b).put((byte)a);
                    }
                }
            }
            return out.array();
    }
}
