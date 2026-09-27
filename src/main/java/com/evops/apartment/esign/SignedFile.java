package com.evops.apartment.esign;

/**
 * 平台回传的已签署文件（演示环境为平台出具的文本凭证，生产可换成对象存储地址/字节流）。
 */
public class SignedFile {
    private final String fileName;
    private final String content;

    public SignedFile(String fileName, String content) {
        this.fileName = fileName;
        this.content = content;
    }

    public String getFileName() { return fileName; }
    public String getContent() { return content; }
}
