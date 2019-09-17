/**
 *     Copyright 2015-2016 Austin Keener
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.dv8tion.discord.bridge.endpoint;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.validator.UrlValidator;
import org.pircbotx.Colors;

import net.dv8tion.discord.Yuri;


public abstract class EndPoint
{
    protected EndPointType connectionType;
    protected boolean connected;

    public abstract EndPointInfo toEndPointInfo();
    public abstract int getMaxMessageLength();
    public abstract void sendMessage(String message);
    public abstract void sendMessage(EndPointMessage message);
	public abstract void sendAction(String message);
	public abstract void sendAction(EndPointMessage message);

    protected EndPoint(EndPointType connectionType)
    {
        this.connectionType = connectionType;
        connected = true;
    }

    public boolean isConnected()
    {
        return connected;
    }

    protected void setConnected(boolean connected)
    {
        this.connected = connected;
    }

    public EndPointType getType()
    {
        return connectionType;
    }

    public ArrayList<String> divideMessageForSending(String message)
    {
    	

    	
    	// Pattern for recognizing a URL, based off RFC 3986
    	Pattern urlPattern = Pattern.compile(
    	        "(?:^|[\\W])((ht|f)tp(s?):\\/\\/|www\\.)"
    	                + "(([\\w\\-]+\\.){1,}?([\\w\\-.~]+\\/?)*"
    	                + "[\\p{Alnum}.,%_=?&#\\-+()\\[\\]\\*$~@!:/{};']*)",
    	        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);
    	Matcher matcher = urlPattern.matcher(message);
    	if (!matcher.find()) {
        	Pattern boldPattern = Pattern.compile("(\\*\\*([^\\*\\*]*)\\*\\*)");
        	Matcher boldMatcher = boldPattern.matcher(message);
        	while (boldMatcher.find()) {
        	    message = message.replace(boldMatcher.group(1), Colors.BOLD + boldMatcher.group(2) + Colors.NORMAL);
        	}
        	
        	Pattern underlinePattern = Pattern.compile("(\\_\\_([^\\_\\_]*)\\_\\_)");
        	Matcher underlineMatcher = underlinePattern.matcher(message);
        	while (underlineMatcher.find()) {
        	    message = message.replace(underlineMatcher.group(1), Colors.UNDERLINE + underlineMatcher.group(2) + Colors.NORMAL);
        	}
        	
        	Pattern italicPattern = Pattern.compile("(\\*([^\\*]*)\\*)");
        	Matcher italicMatcher = italicPattern.matcher(message);
        	while (italicMatcher.find()) {
        	    message = message.replace(italicMatcher.group(1), Colors.ITALICS + italicMatcher.group(2) + Colors.NORMAL);
        	}
    		
        	Pattern italicPattern2 = Pattern.compile("(\\_([^\\_]*)\\_)");
        	Matcher italicMatcher2 = italicPattern2.matcher(message);
        	while (italicMatcher2.find()) {
        	    message = message.replace(italicMatcher2.group(1), Colors.ITALICS + italicMatcher2.group(2) + Colors.NORMAL);
        	}
        	
        	Pattern spoilerPattern = Pattern.compile("(\\|\\|([^\\|\\|]*)\\|\\|)");
        	Matcher spoilerMatcher = spoilerPattern.matcher(message);
        	while (spoilerMatcher.find()) {
        	    message = message.replace(spoilerMatcher.group(1), "SPOILER: " + Colors.BLACK +",1" + spoilerMatcher.group(2) + Colors.NORMAL);
        	}
    	}



        ArrayList<String> messageParts = new ArrayList<String>();
        while (message.length() >  getMaxMessageLength())
        {         	
            //Finds where the last complete word is in the IrcConnection.MAX_LINE_LENGTH length character string.
            int lastSpace = message.substring(0, getMaxMessageLength()).lastIndexOf(" ");
            String smallerLine;
            if (lastSpace != -1)
            {
                smallerLine = message.substring(0, lastSpace);
                message = message.substring(lastSpace + 1);   //Don't include the space.
            }
            else
            {
                smallerLine = message.substring(0, getMaxMessageLength());
                message = message.substring(getMaxMessageLength());
            }
            messageParts.add(smallerLine);
        }
        messageParts.add(message);
        return messageParts;
    }
}
