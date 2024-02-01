import com.sustain.entities.custom.Ce_ParticipantReportNumbers
import com.sustain.entities.custom.Ce_SubpoenaTracking
import com.sustain.mail.Attachments
import com.sustain.mail.model.AuditedEmail;
import com.sustain.expression.Where
import com.sustain.person.model.CaseContact;

import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.sql.Timestamp;
import com.sustain.lookuplist.model.LookupItem;
import com.sustain.form.form.FormUtils;
import org.apache.commons.io.FileUtils;
import com.sustain.util.ZipUtil;
import java.nio.file.Path;
import com.sustain.DomainObject;
import com.sustain.mail.Attachment;
import java.time.LocalDate;
import java.lang.Exception;
import java.io.PrintWriter;
import com.sustain.calendar.model.ScheduledEvent;
import com.sustain.cases.model.Case;
import com.sustain.entities.custom.Ce_SubpoenaTracking;
import com.sustain.util.RichList;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import com.sustain.roa.model.ROAMessage;
import com.sustain.person.model.Person;
import com.sustain.condition.model.Condition;


def int count = 0;
def Timestamp fromTimestamp = Timestamp.valueOf(LocalDateTime.now().minusDays(3L));
def Timestamp toTimestamp = Timestamp.valueOf(LocalDateTime.now());

def Where where = new Where();
where.addGreaterThanOrEquals("dateSubpoenasGenerated", fromTimestamp);
where.addLessThanOrEquals("dateSubpoenasGenerated", toTimestamp);
where.addEquals("ce_SubpoenaTrackings.emailed", false);
where.addIsNotNull("ce_SubpoenaTrackings.participant");
where.addIsNotNull("ce_SubpoenaTrackings.document");
File log = new File("\\\\torreypines\\ecourts\\subpoena\\emailLog_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd-yy@hhmms"))}.txt");
PrintWriter writer = new PrintWriter(log);
HashSet events = new HashSet();
_event != null ? events.add(_event) : events.addAll(DomainObject.find(ScheduledEvent.class, where));


writer.println("events to process: ${events.size()}");

for (ScheduledEvent event in events) {
    def ScheduledEvent subpoenaedEvent = event;
    def Case cse = subpoenaedEvent.case;
    def String caseNumber = cse.caseNumber;
    def String crtNumber = !cse.collect("otherCaseNumbers[type == 'CRT'].number").isEmpty() ? cse.collect("otherCaseNumbers[type == 'CRT']").orderBy("id").last().number : "";
    def String agencyName = cse.collect("ce_Participants[type == 'AGENCY'].person.organizationName").first();
    def String agencyReportNumber = cse.collect("ce_Participants[type == 'AGENCY'].ce_ParticipantReportNumbers[type == 'LAG'].number").first();

    def RichList<Ce_SubpoenaTracking> personalServiceSubpoenas = subpoenaedEvent.collect("ce_SubpoenaTrackings[ participant != null && document != null && (serviceMethod == 'PERSONINV' || serviceMethod == 'BOTH')]");
    personalServiceSubpoenas = _includePreviousSubpoenas == null || _includePreviousSubpoenas == false ? subpoenaedEvent.collect("ce_SubpoenaTrackings[ participant != null && document != null && (serviceMethod == 'PERSONINV' || serviceMethod == 'BOTH') && (emailed == null || emailed == #p1)]", false) : personalServiceSubpoenas;

    def RichList<Ce_SubpoenaTracking> mailSubpoenas = subpoenaedEvent.collect("ce_SubpoenaTrackings[ participant != null && document != null && (serviceMethod == 'MAIL' || serviceMethod == 'BOTH')]");
    mailSubpoenas = _includePreviousSubpoenas == null || _includePreviousSubpoenas == false ? subpoenaedEvent.collect("ce_SubpoenaTrackings[ participant != null && document != null && (serviceMethod == 'MAIL' || serviceMethod == 'BOTH') && (emailed == null || emailed == #p1)]", false) : mailSubpoenas;

    def String subject;
    def String body;
    def String formattedDate;
    if (subpoenaedEvent.startDateTime.getClass() == LocalDateTime) {
        formattedDate = subpoenaedEvent.startDateTime.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE);
    } else {
        formattedDate = new Timestamp(subpoenaedEvent.startDateTime.getTime()).toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    subject = "${formattedDate}; Criminal Subpoena - DA No. ${caseNumber}; ${crtNumber}; ${agencyName} ${agencyReportNumber}".toString();

    if (cse.caseType == "F" || LookupItem.getLabel('CASE_TYPE', cse.caseType).contains("Felony")) {
        body = "Please send replies to win@fresnocountyca.gov \r\n";
    }
    if (cse.caseType == "M" || LookupItem.getLabel('CASE_TYPE', cse.caseType).contains("Misdemeanor")) {
        body = "Please send replies to MisdWin@fresnocountyca.gov \r\n";
    }

    def HashSet<Person> agencyList = new HashSet();
    def HashSet<Person> spuList = new HashSet();
    def HashSet<Person> officersList = new HashSet();
    def HashSet<Person> spuOfficersList = new HashSet();

    for (Ce_SubpoenaTracking subpoenaTracking in personalServiceSubpoenas) {
        def RichList<String> officerContacts = subpoenaTracking.participant.person.collect("contacts[type == 'EB' && effectiveTo == null].contact");
        if (!officerContacts.isEmpty()) {

            def File file = subpoenaTracking.document.file;
            def Person agency = subpoenaTracking.participant.person.collect("relationships[relationshipType == 'EMPLOYMENT' && relationshipSubType == 'EMPLOYER' && endDate == null].relatedPerson").find({ it -> it != null });

            agency = agency == null ? subpoenaTracking.participant.person.collect("relationships[endDate == null].relatedPerson[personType == 'AGENCY']").find({ it -> it != null }) : agency;

            agency == null ?: spuList.add(agency);
            subpoenaTracking.participant.person == null ?: spuOfficersList.add(subpoenaTracking.participant.person);

            createRoaMessage(event.case, "1.Subpoena to process ${Timestamp.valueOf(LocalDateTime.now())} : ", "witness: ${subpoenaTracking.participant.person.title}; related agency: ${agency?.title}");
            if (agency == null) {
                setSubpoenaEmailedBoolean(subpoenaTracking, false);
                sendMail(subpoenaTracking, officerContacts, subject, body, file);
            }
            if (agency != null && !Condition.get('Person Type is Agency (Agencies which do not want their officers to receive subpoenas)').isTrue(agency)) {
                sendMail(subpoenaTracking, officerContacts, subject, body, file);
            }
        }
    }

    for (Ce_SubpoenaTracking subpoenaTracking in mailSubpoenas) {

        def RichList<String> officerContacts = subpoenaTracking.participant.person.collect("contacts[type == 'EB' && effectiveTo == null].contact");
        if (!officerContacts.isEmpty()) {

            def File file = subpoenaTracking.document.file;
            def Person agency = subpoenaTracking.participant.person.collect("relationships[relationshipType == 'EMPLOYMENT' && relationshipSubType == 'EMPLOYER' && endDate == null].relatedPerson").find({ it -> it != null });

            agency = agency == null ? subpoenaTracking.participant.person.collect("relationships[endDate == null].relatedPerson[personType == 'AGENCY']").find({ it -> it != null }) : agency;

            agency == null ?: agencyList.add(agency);
            subpoenaTracking.participant.person == null ?: officersList.add(subpoenaTracking.participant.person);

            createRoaMessage(event.case, "1.Subpoena to process ${Timestamp.valueOf(LocalDateTime.now())} : ", "witness: ${subpoenaTracking.participant.person.title}; related agency: ${agency?.title}");
            if (agency == null) {
                setSubpoenaEmailedBoolean(subpoenaTracking, false);
                sendMail(subpoenaTracking, officerContacts, subject, body, file);
            }
            if (agency != null && !Condition.get('Person Type is Agency (Agencies which do not want their officers to receive subpoenas)').isTrue(agency)) {
                sendMail(subpoenaTracking, officerContacts, subject, body, file);
            }
        }
    }

    for (Person agency in agencyList) {
        def RichList<Ce_SubpoenaTracking> agencySubpoenaTrackings = new RichList();

        for (contact in agency.collect("contacts[type == 'EB' && effectiveTo == null].contact")) {
            for (officer in officersList) {
                def RichList<Ce_SubpoenaTracking> agencySubpoenaTracking = subpoenaedEvent.collect("ce_SubpoenaTrackings[participant != null && participant.person == #p1 && document != null && serviceMethod == 'MAIL' ]", officer, false);
                def RichList<Ce_SubpoenaTracking> agencySubpoenaTracking1 = subpoenaedEvent.collect("ce_SubpoenaTrackings[participant != null && participant.person == #p1 && document != null && serviceMethod == 'BOTH' ]", officer, false);

                if (!agencySubpoenaTracking.isEmpty() && !agency.collect("relationships[relatedPerson == #p1 && endDate == null]", officer).isEmpty()) {
                    agencySubpoenaTrackings.addAll(agencySubpoenaTracking);
                }
                if (!agencySubpoenaTracking1.isEmpty() && !agency.collect("relationships[relatedPerson == #p1 && endDate == null]", officer).isEmpty()) {
                    agencySubpoenaTrackings.addAll(agencySubpoenaTracking1);
                }
            }

            createRoaMessage(event.case, "3.Agency subpoena processing ${Timestamp.valueOf(LocalDateTime.now())} : ", "${agency?.title} has ${agencySubpoenaTrackings.size()} subpoenas for court event: ${event.title} and the following witnesses ${agencySubpoenaTrackings.participant.title.join(", ")}");
            if (!agencySubpoenaTrackings.isEmpty()) {
                if (agencySubpoenaTrackings.size() > 100) {
                    def ArrayList collection = splitCollection(agencySubpoenaTrackings);
                    collection.each({
                        it ->
                            if (it.size() > 100) {
                                ArrayList splitCollection = splitCollection(it);
                                splitCollection.each({
                                    it2 ->
                                        sendAgencyMail(agencySubpoenaTrackings.first(), agency, contact, subject, body, it2.document.file, it2, writer);
                                });
                            } else if (it.size() <= 100) {
                                sendAgencyMail(agencySubpoenaTrackings.first(), agency, contact, subject, body, it.document.file, it, writer);
                            }
                    });
                } else if (agencySubpoenaTrackings.size() <= 100) {
                    sendAgencyMail(agencySubpoenaTrackings.first(), agency, contact, subject, body, agencySubpoenaTrackings.document.file, agencySubpoenaTrackings.orderBy("id"), writer);
                }
            }
        }
    }

    for (Person agency in spuList) {

        def RichList<Ce_SubpoenaTracking> agencySubpoenaTrackings = new RichList();
        for (officer in spuOfficersList) {
            def RichList<Ce_SubpoenaTracking> agencySubpoenaTracking = subpoenaedEvent.collect("ce_SubpoenaTrackings[participant != null && participant.person == #p1 && document != null ]", officer, false);

            if (!agencySubpoenaTracking.isEmpty() && !agency.collect("relationships[relatedPerson == #p1 && endDate == null]", officer).isEmpty()) {
                agencySubpoenaTrackings.addAll(agencySubpoenaTracking);
            }
        }

        createRoaMessage(event.case, "3.Agency subpoena processing ${Timestamp.valueOf(LocalDateTime.now())} : ", "${agency?.title} has ${agencySubpoenaTrackings.size()} subpoenas for court event: ${event.title} and the following witnesses ${agencySubpoenaTrackings.participant.title.join(", ")}");
        def String contact = "spu@fresnocountyca.gov";
        if (!agencySubpoenaTrackings.isEmpty()) {
            if (agencySubpoenaTrackings.size() > 100) {
                ArrayList collection = splitCollection(agencySubpoenaTrackings);
                collection.each({
                    it ->
                        if (it.size() > 100) {
                            ArrayList splitCollection = splitCollection(it);
                            splitCollection.each({
                                it2 ->
                                    sendAgencyMail(agencySubpoenaTrackings.first(), agency, contact, subject, body, it2.document.file, it2, writer);
                            });
                        } else if (it.size() <= 100) {
                            sendAgencyMail(agencySubpoenaTrackings.first(), agency, contact, subject, body, it.document.file, it, writer);
                        }
                });
            } else if (agencySubpoenaTrackings.size() <= 100) {
                sendAgencyMail(agencySubpoenaTrackings.first(), agency, contact, subject, body, agencySubpoenaTrackings.document.file, agencySubpoenaTrackings.orderBy("id"), writer);
            }
        }
    }
}
writer.println("count ${count}");
writer.flush();
writer.close();


public void sendMail(Ce_SubpoenaTracking subTracking, RichList recipient, String subject, String body, File file) {

    Attachments attachments = new Attachments(file);
    def String noteTitle;
    def String noteContent;
    try {
        if (recipient.isEmpty()) {
            throw new Exception("null email address");
        }
        if (_sendMail == true) {
//            mailManager.sendMail(recipient.first(), subject, body, attachments);
            logger.debug("sending officer subpoena ${recipient.first()}");
        }
        noteTitle = "2.Document Emailed Successfully ${Timestamp.valueOf(LocalDateTime.now())}".toString();
        noteContent = "${subTracking} Document: ${subTracking.document} ${subTracking.document.title} email for: Court Event: ${subTracking.scheduledEvent} ${subTracking.scheduledEvent.title} \r\nWitness: ${subTracking.participant.person.fullName}; ${recipient}.".toString();
        createRoaMessage(subTracking.case, noteTitle, noteContent);
    } catch (Exception e) {
        logger.debug("Exception:1: ${e.getMessage()}")
        noteTitle = "2.Document Email Failed ${Timestamp.valueOf(LocalDateTime.now())}".toString();
        noteContent = "${subTracking} Document: ${subTracking.document} ${subTracking.document.title} email for: Court Event: ${subTracking.scheduledEvent} ${subTracking.scheduledEvent.title} \r\nWitness: ${subTracking.participant.person.fullName}, review officers email address or print and mail.".toString();
        createRoaMessage(subTracking.case, noteTitle, noteContent);
    }
}

public void sendAgencyMail(Ce_SubpoenaTracking subTracking, Person agency, String recipient, String subject, String body, Collection<File> files, Object agencySubpoenaTrackings, PrintWriter writer) {

    Path zippedFilePath = Files.createTempFile("subpoena_email_interface", ".zip");

    File zippedFile = zippedFilePath.toFile();
    zipFilesWithUtility(zippedFile, files);

    ArrayList zippedFiles = [zippedFile];
    File[] fileArray = zippedFiles.toArray();
    Attachments attachments = new Attachments(fileArray);
    try {
        if (recipient == null || recipient.isEmpty()) {
            throw new Exception("null email address");
        }
        if (files.isEmpty()) {
            throw new Exception("no attachments");
        }
        RichList toEmails = new RichList();
        RichList ccEmails = new RichList();
        RichList bccEmails = new RichList();
        toEmails.add(recipient);
        ccEmails.add("daepro@fresnocountyca.gov");
        ccEmails.add("jcamarena@journaltech.com");
        if (_sendMail == true) {
//            mailManager.sendMailToAll(toEmails, ccEmails, bccEmails, subject, body, attachments);
            logger.debug("sending agency subpoena: ${toEmails}");
        }
        //writer.print(" status: successfully emailed subpoena for ");
        noteTitle = "4.${agency.organizationName} Subpoena Documents Emailed Successfully ${Timestamp.valueOf(LocalDateTime.now())}".toString();
        noteContent = "";
        for (agencySubpoenaTracking in agencySubpoenaTrackings) {
            //writer.print(" ${agencySubpoenaTracking.participant.person.fml} | ".toString());
            noteContent += "${agencySubpoenaTracking} <br>${agencySubpoenaTracking.document} <br>${agencySubpoenaTracking.document.dateCreated.getClass() == Timestamp ? agencySubpoenaTracking.document.dateCreated.toLocalDateTime().format(DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm a")) : new Timestamp(agencySubpoenaTracking.document.dateCreated.getTime()).toLocalDateTime().format(DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm a"))} ${agencySubpoenaTracking.document.docDef.shortName} <br>Court Event: ${agencySubpoenaTracking.scheduledEvent} ${agencySubpoenaTracking.scheduledEvent.title} \r\nWitness: ${agencySubpoenaTracking.participant.person.fullName}<br>".toString();
            setSubpoenaEmailedBoolean(agencySubpoenaTracking, true);
        }
        //writer.println();
        noteContent += "emailed to: ${toEmails}, cc to: ${ccEmails}".toString();
        createRoaMessage(subTracking.case, noteTitle, noteContent, subTracking.scheduledEvent);
        if (subTracking.scheduledEvent.updateReason != "SUBGEN2") {
            subTracking.scheduledEvent.updateReason = "SUBGEN2";
            subTracking.scheduledEvent.lastUpdated = Timestamp.valueOf(LocalDateTime.now());
            withTx { subTracking.scheduledEvent.saveOrUpdate() }
        }
    } catch (Exception e) {
        logger.debug("Exception:2: ${e.getMessage()}");
        //writer.print("status: failed agency email");
        noteTitle = "4.${agency.organizationName} Subpoena Documents Email Failed ${Timestamp.valueOf(LocalDateTime.now())}".toString();
        noteContent = "Exception Message: ${e.getMessage()}\r\nExeption Cause: ${e.getCause()}";
        for (agencySubpoenaTracking in agencySubpoenaTrackings) {
            noteContent += "${agencySubpoenaTracking} <br>${agencySubpoenaTracking.document} <br>${agencySubpoenaTracking.document.dateCreated.getClass() == Timestamp ? agencySubpoenaTracking.document.dateCreated.toLocalDateTime().format(DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm a")) : new Timestamp(agencySubpoenaTracking.document.dateCreated.getTime()).toLocalDateTime().format(DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm a"))} ${agencySubpoenaTracking.document.docDef.shortName} <br>Court Event: ${agencySubpoenaTracking.scheduledEvent} ${agencySubpoenaTracking.scheduledEvent.title} \r\nWitness: ${agencySubpoenaTracking.participant.person.fullName}<br>".toString();
        }
        subTracking.scheduledEvent.updateReason = "SUBGEN3";
        withTx { subTracking.scheduledEvent.saveOrUpdate() }
        createRoaMessage(subTracking.case, noteTitle, noteContent, subTracking.scheduledEvent);
    } finally {
        Files.deleteIfExists(zippedFilePath);
    }

}

void createRoaMessage(Case thisCase, String title, String content, ScheduledEvent event) {
    def ROAMessage roa = new ROAMessage();
    noteTitle = title != null ? "${title} <br> " : "";
    noteContent = content != null && !content.trim().isEmpty() ? "${content}" : "";
    roa.message = "${noteTitle}${noteContent}".toString();
    roa.case = thisCase;
    roa.category = "DOC";
    roa.subCategory = "EMAILED";
    roa.timestamp = Timestamp.valueOf(LocalDateTime.now());
    thisCase.add(roa, "roaMessages");
    withTx { thisCase.saveOrUpdate() };
}

void createRoaMessage(Case thisCase, String title, String content) {
    ROAMessage roa = new ROAMessage();
    noteTitle = title != null ? "${title} <br> " : "";
    noteContent = content != null && !content.trim().isEmpty() ? "${content}" : "";
    roa.message = "${noteTitle}${noteContent}".toString();
    roa.case = thisCase;
    roa.category = "DOC";
    roa.subCategory = "EMAILED";
    roa.timestamp = Timestamp.valueOf(LocalDateTime.now());
    thisCase.add(roa, "roaMessages");
    withTx { thisCase.saveOrUpdate() };
}

public void setSubpoenaEmailedBoolean(Ce_SubpoenaTracking subTrk, Boolean bool) {
    if (subTrk.emailed != true && bool == true) {
        subTrk.emailed = bool;
        subTrk.status = "EMAILED";
        subTrk.statusDate = Timestamp.valueOf(LocalDateTime.now());
        withTx { subTrk.saveOrUpdate() }
    } else if (bool == false) {
        subTrk.emailed = bool;
        subTrk.status = "REVIEWAGENCY";
        subTrk.statusDate = Timestamp.valueOf(LocalDateTime.now());
        withTx { subTrk.saveOrUpdate() }
    }
}

public ArrayList splitCollection(Object agencySubpoenaTrackings) {
    return agencySubpoenaTrackings.split({ it -> agencySubpoenaTrackings.indexOf(it) % 2 == 0 });
}


public void zipFilesWithUtility(File zipFile, Collection<File> filesToZip) {
    ZipUtil.zipFiles(zipFile, filesToZip);
}