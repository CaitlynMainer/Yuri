package net.dv8tion.discord.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Created by Administrator on 12/31/2016.
 */
public class makeTiny {
    public static String getTinyURL(String args) {
        try {
            URL url = new URL("http://tinyurl.com/api-create.php?url=" + args);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) {
                throw new RuntimeException("Failed : HTTP error code : " + conn.getResponseCode());
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    (conn.getInputStream())));
            String output;
            String actualOut = "";
            while ((output = br.readLine()) != null) {
                actualOut = output;
                System.out.println(output);
            }
            conn.disconnect();
            return actualOut;

        } catch (MalformedURLException e) {

            e.printStackTrace();

        } catch (IOException e) {

            e.printStackTrace();

        }
        return args.toString();
    }
}
