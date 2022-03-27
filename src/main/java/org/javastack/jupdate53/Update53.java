package org.javastack.jupdate53;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.Change;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.ChangeBatch;
import software.amazon.awssdk.services.route53.model.ChangeInfo;
import software.amazon.awssdk.services.route53.model.ChangeResourceRecordSetsRequest;
import software.amazon.awssdk.services.route53.model.ChangeResourceRecordSetsResponse;
import software.amazon.awssdk.services.route53.model.ChangeStatus;
import software.amazon.awssdk.services.route53.model.GetChangeRequest;
import software.amazon.awssdk.services.route53.model.ListResourceRecordSetsRequest;
import software.amazon.awssdk.services.route53.model.ListResourceRecordSetsResponse;
import software.amazon.awssdk.services.route53.model.RRType;
import software.amazon.awssdk.services.route53.model.ResourceRecord;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;
import software.amazon.awssdk.services.route53.model.Route53Exception;

/**
 * Use EC2-role or put your credentials on a file:
 *
 * <pre>
 * # ${HOME}/.aws/credentials
 * [default]
 * aws_access_key_id = YOUR_AWS_ACCESS_KEY_ID
 * aws_secret_access_key = YOUR_AWS_SECRET_ACCESS_KEY
 * </pre>
 *
 * https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/get-started.html
 *
 * @see software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
 */
public class Update53 {
	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
	private static final int MAX_WAIT_SECONDS = 60;

	/*
	 * Simple and direct update of RRtype.A record
	 */
	public static ChangeStatus updateRecordTypeA(final Route53Client route53Client, //
			final String hostedZoneId, //
			final List<String> fqdn, //
			final long ttl, //
			final String ip, //
			boolean wait) {
		final Collection<Change> changes = fqdn.stream().map(n -> {
			final ResourceRecord rrnew = ResourceRecord.builder().value(ip).build();
			final ResourceRecordSet rrsnew = ResourceRecordSet.builder().name(n).resourceRecords(rrnew)
					.ttl(Long.valueOf(ttl)).type(RRType.A).build();
			return Change.builder().action(ChangeAction.UPSERT).resourceRecordSet(rrsnew).build();
		}).collect(Collectors.toList());
		final ChangeBatch changeBatch = ChangeBatch.builder().changes(changes).build();
		final ChangeResourceRecordSetsRequest request = ChangeResourceRecordSetsRequest.builder()
				.hostedZoneId(hostedZoneId).changeBatch(changeBatch).build();

		for (ResourceRecordSet record : changes.stream().map(Change::resourceRecordSet)
				.collect(Collectors.toList())) {
			log.info("Update Record name: {} type: {} ttl: {} records: {}", record.name(), record.type(),
					record.ttl(), record.resourceRecords().stream().map(ResourceRecord::value)
							.collect(Collectors.toList()));
		}

		final ChangeResourceRecordSetsResponse updateResourceRecordSets = route53Client
				.changeResourceRecordSets(request);
		if (!wait) {
			return updateResourceRecordSets.changeInfo().status();
		}
		final GetChangeRequest cr = GetChangeRequest.builder().id(updateResourceRecordSets.changeInfo().id())
				.build();
		for (int i = 0, waited = 0; waited < MAX_WAIT_SECONDS; i++) {
			final ChangeInfo ci = route53Client.getChange(cr).changeInfo();
			log.info("The RecordSets update: {}", ci.statusAsString());
			if (ci.status() == ChangeStatus.INSYNC) {
				return ChangeStatus.INSYNC;
			}
			try {
				final int w = Math.max(1, Math.min(i * 2, 5));
				Thread.sleep(w * 1000);
				waited += w;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		return ChangeStatus.PENDING;
	}

	/*
	 * Retrieve complete zone and filter SOA register
	 */
	@Deprecated
	public static List<ResourceRecordSet> findSOA(final Route53Client route53Client, //
			final String hostedZoneId) {
		final ListResourceRecordSetsRequest request = ListResourceRecordSetsRequest.builder()
				.hostedZoneId(hostedZoneId) //
				.build();

		final ListResourceRecordSetsResponse listResourceRecordSets = route53Client
				.listResourceRecordSets(request);
		final List<ResourceRecordSet> matchRecords = listResourceRecordSets.resourceRecordSets().stream()
				.filter(c -> (c.type() == RRType.SOA)).collect(Collectors.toList());
		for (final ResourceRecordSet record : matchRecords) {
			log.info("Match SOA name: {} records: {}", record.name(), record.resourceRecords().stream()
					.map(ResourceRecord::value).collect(Collectors.toList()));
		}
		return matchRecords;
	}

	/*
	 * Find specified fqdn or wildcard
	 */
	@Deprecated
	public static List<ResourceRecordSet> findResourceRecord(final Route53Client route53Client, //
			final String hostedZoneId, //
			final String fqdnMatch) {
		final String m1 = fqdnMatch + ".";
		final String m2 = "\\052." + fqdnMatch + "."; // Wildcard
		final ListResourceRecordSetsRequest request = ListResourceRecordSetsRequest.builder()
				.hostedZoneId(hostedZoneId).maxItems("42") //
				.startRecordName(m1).startRecordType(RRType.A) //
				.build();

		final ListResourceRecordSetsResponse listResourceRecordSets = route53Client
				.listResourceRecordSets(request);
		final List<ResourceRecordSet> matchRecords = listResourceRecordSets.resourceRecordSets().stream()
				.filter(c -> (c.type() == RRType.A) && (c.name().equals(m1) || c.name().equals(m2)))
				.collect(Collectors.toList());
		for (ResourceRecordSet record : matchRecords) {
			log.info("Match Record name: {} type: {} records: {}", record.name(), record.type(), record
					.resourceRecords().stream().map(ResourceRecord::value).collect(Collectors.toList()));
		}
		return matchRecords;
	}

	/*
	 * Update a list of records
	 */
	@Deprecated
	public static ChangeStatus updateResourceRecords(final Route53Client route53Client, //
			final String hostedZoneId, //
			final List<ResourceRecordSet> rrsList, //
			final String ip, //
			final boolean wait) {
		final Collection<Change> changes = rrsList.stream().map(r -> {
			final ResourceRecord rrnew = ResourceRecord.builder().value(ip).build();
			final ResourceRecordSet rrsnew = ResourceRecordSet.builder().name(r.name()).resourceRecords(rrnew)
					.ttl(r.ttl()).type(r.type()).build();
			return Change.builder().action(ChangeAction.UPSERT).resourceRecordSet(rrsnew).build();
		}).collect(Collectors.toList());
		final ChangeBatch changeBatch = ChangeBatch.builder().changes(changes).build();
		final ChangeResourceRecordSetsRequest request = ChangeResourceRecordSetsRequest.builder()
				.hostedZoneId(hostedZoneId).changeBatch(changeBatch).build();

		for (final ResourceRecordSet record : changes.stream().map(Change::resourceRecordSet)
				.collect(Collectors.toList())) {
			log.info("Update Record name: {} type: {} ttl: {} records: {}", record.name(), record.type(),
					record.ttl(), record.resourceRecords().stream().map(ResourceRecord::value)
							.collect(Collectors.toList()));
		}

		final ChangeResourceRecordSetsResponse updateResourceRecordSets = route53Client
				.changeResourceRecordSets(request);
		if (!wait) {
			return updateResourceRecordSets.changeInfo().status();
		}
		final GetChangeRequest cr = GetChangeRequest.builder().id(updateResourceRecordSets.changeInfo().id())
				.build();
		for (int i = 0, waited = 0; waited < MAX_WAIT_SECONDS; i++) {
			final ChangeInfo ci = route53Client.getChange(cr).changeInfo();
			log.info("The RecordSets update: {}", ci.statusAsString());
			if (ci.status() == ChangeStatus.INSYNC) {
				return ChangeStatus.INSYNC;
			}
			try {
				final int w = Math.max(1, Math.min(i * 2, 5));
				Thread.sleep(w * 1000);
				waited += w;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		return ChangeStatus.PENDING;
	}

	/*
	 * Simple Test
	 */
	public static void main(String[] args) {
		final String hostedZoneId = args[0];
		final String fqdnMatch = args[1];
		final String ip = "127.42.0." + ((int) (Math.random() * 1000 % 256)); // Use random looback IP
		// First Step: https://aws.amazon.com/sdk-for-java/
		try (final Route53Client route53Client = Route53Client.builder().region(Region.AWS_GLOBAL).build()) {
			// List<ResourceRecordSet> rrsList = findResourceRecord(route53Client, hostedZoneId, fqdnMatch);
			// updateResourceRecords(route53Client, hostedZoneId, rrsList, ip, true);
			updateRecordTypeA(route53Client, hostedZoneId, Arrays.asList(fqdnMatch), 3, ip, true);
			// findSOA(route53Client, hostedZoneId);
		} catch (Route53Exception e) {
			log.error("Route53Exception: {}", e.getMessage());
			System.exit(1);
		}
	}
}
