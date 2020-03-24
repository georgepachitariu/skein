package com.anaconda.skein;

import org.apache.commons.codec.binary.Base64;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.token.Token;
import java.io.File;
import java.io.IOException;

public class UserTools {
    final static String hiveTokenKind = "HIVE_DELEGATION_TOKEN";

    // Print from the Delegation Token file the Username (Identifier) and Password.
    // With user + password you can create a Hive connection
    private static void printDelegationTokenIdandPassForHive(String tokenFilepath) throws IOException {
        Credentials credentials = Credentials.readTokenStorageFile(new File(tokenFilepath), null);
        for(Token t : credentials.getAllTokens()) {
            if(t.getKind().toString().equals(hiveTokenKind)) {
                String username = new String(Base64.encodeBase64(t.getIdentifier()));
                String password = new String(Base64.encodeBase64(t.getPassword()));
                System.out.println( username + "\n" + password);
            }
        }
    }

    public static void main(String[] args) throws IOException {
        String help = "Usage: To print username and password from inside the Hive delegation token: \n" +
                "[print-hive-user-pass]" + "<delegation-token-file-path>";

        switch(args[0]) {
            case "print-hive-user-pass": UserTools.printDelegationTokenIdandPassForHive(args[1]);
                                            break;
            default: System.out.println(help);
                     System.exit(1);
        }
    }
}
