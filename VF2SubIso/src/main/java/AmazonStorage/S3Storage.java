package AmazonStorage;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import util.ConfigParser;

import java.io.*;

public class S3Storage {

    public static void upload(String bucketName, String key, Object obj)
    {
        String fileName="./tempGraph.ser";
        try {
            FileOutputStream file = new FileOutputStream(fileName);
            ObjectOutputStream out = new ObjectOutputStream(file);
            out.writeObject(obj);
            out.close();
            file.close();
            System.out.println("Object has been serialized.");

            System.out.println("Uploading to Amazon S3");

            final AmazonS3 s3Client = AmazonS3ClientBuilder.standard().withRegion(ConfigParser.region).build();
            File fileToBeUploaded = new File(fileName);
            PutObjectRequest request = new PutObjectRequest(bucketName, key, fileToBeUploaded).withCannedAcl(CannedAccessControlList.PublicRead);
            PutObjectResult result = s3Client.putObject(request);
            System.out.println("Uploading Done. [Bucket name: " + bucketName + "] [Key: " + key + "]");

            System.out.println("Cleaning up the temporary storage...");
            fileToBeUploaded.delete();
            System.out.println("All done.");
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void upload(String bucketName, String key, String textToBeUploaded)
    {
        String fileName="./tempGraph.ser";
        try {
            FileWriter file = new FileWriter(fileName);
            file.write(textToBeUploaded);
            file.close();
            System.out.println("Text has been stored in the file.");

            System.out.println("Uploading to Amazon S3");

            final AmazonS3 s3Client = AmazonS3ClientBuilder.standard().withRegion(ConfigParser.region).build();
            File fileToBeUploaded = new File(fileName);
            PutObjectRequest request = new PutObjectRequest(bucketName, key, fileToBeUploaded).withCannedAcl(CannedAccessControlList.PublicRead);
            PutObjectResult result = s3Client.putObject(request);
            System.out.println("Uploading Done. [Bucket name: " + bucketName + "] [Key: " + key + "]");

            System.out.println("Cleaning up the temporary storage...");
            fileToBeUploaded.delete();
            System.out.println("All done.");
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Object downloadObject(String bucketName, String key)
    {
        Object obj=null;
        try
        {
            S3Object fullObject;
            AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
                    .withRegion(ConfigParser.region)
                    .build();
            System.out.println("Downloading the object from Amazon S3 - Bucket name: " + bucketName +" - Key: " + key);
            fullObject = s3Client.getObject(new GetObjectRequest(bucketName, key));

            ObjectInputStream in = new ObjectInputStream(fullObject.getObjectContent());
            obj = in.readObject();
            in.close();
            fullObject.close();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return obj;
    }

    public static StringBuilder downloadText(String bucketName, String key)
    {
        StringBuilder sb=new StringBuilder();
        try
        {
            S3Object fullObject;
            AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
                    .withRegion(ConfigParser.region)
                    .build();
            System.out.println("Downloading the object from Amazon S3 - Bucket name: " + bucketName +" - Key: " + key);
            fullObject = s3Client.getObject(new GetObjectRequest(bucketName, key));

            BufferedReader br = new BufferedReader(new InputStreamReader(fullObject.getObjectContent()));
            String line= br.readLine();
            while (line!=null) {
                sb.append(line).append("\n");
                line=br.readLine();
            }
            br.close();
            fullObject.close();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return sb;
    }

}