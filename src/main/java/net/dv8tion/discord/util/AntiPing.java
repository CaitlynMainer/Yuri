package net.dv8tion.discord.util;

/**
 * Created by Administrator on 12/31/2016.
 */
public class AntiPing {
    private static String addZeroWidthSpace(String s) {
        final int mid = s.length() / 2; //get the middle of the String
        String[] parts = {s.substring(0, mid),s.substring(mid)};
        return parts[0] + "\u200B" + parts[1];
        //return s.replaceAll(".(?=.)", "$0" + "\u200B");
    }

    public static String antiPing(String nick) {
        return addZeroWidthSpace(nick);
    }
}
