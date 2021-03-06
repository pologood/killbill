/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.subscription.api.user;

import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.joda.time.LocalDate;
import org.killbill.billing.ErrorCode;
import org.killbill.billing.api.TestApiListener.NextEvent;
import org.killbill.billing.catalog.api.BillingActionPolicy;
import org.killbill.billing.catalog.api.BillingPeriod;
import org.killbill.billing.catalog.api.Duration;
import org.killbill.billing.catalog.api.PhaseType;
import org.killbill.billing.catalog.api.Plan;
import org.killbill.billing.catalog.api.PlanPhase;
import org.killbill.billing.catalog.api.PlanPhaseSpecifier;
import org.killbill.billing.catalog.api.PlanSpecifier;
import org.killbill.billing.catalog.api.PriceListSet;
import org.killbill.billing.catalog.api.ProductCategory;
import org.killbill.billing.entitlement.api.Entitlement;
import org.killbill.billing.entitlement.api.SubscriptionEventType;
import org.killbill.billing.invoice.api.DryRunArguments;
import org.killbill.billing.subscription.SubscriptionTestSuiteWithEmbeddedDB;
import org.killbill.billing.subscription.api.SubscriptionBase;
import org.killbill.billing.subscription.api.SubscriptionBaseTransitionType;
import org.killbill.billing.subscription.api.SubscriptionBillingApiException;
import org.killbill.billing.subscription.engine.dao.SubscriptionEventSqlDao;
import org.killbill.billing.subscription.engine.dao.model.SubscriptionEventModelDao;
import org.killbill.billing.subscription.events.SubscriptionBaseEvent;
import org.killbill.billing.subscription.events.user.ApiEvent;
import org.killbill.billing.subscription.events.user.ApiEventType;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.IDBI;
import org.skife.jdbi.v2.tweak.HandleCallback;
import org.testng.Assert;
import org.testng.annotations.Test;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class TestUserApiChangePlan extends SubscriptionTestSuiteWithEmbeddedDB {

    @Inject
    private IDBI dbi;

    private void checkChangePlan(final DefaultSubscriptionBase subscription, final String expProduct, final ProductCategory expCategory,
                                 final BillingPeriod expBillingPeriod, final PhaseType expPhase) {

        final Plan currentPlan = subscription.getCurrentPlan();
        assertNotNull(currentPlan);
        assertEquals(currentPlan.getProduct().getName(), expProduct);
        assertEquals(currentPlan.getProduct().getCategory(), expCategory);
        assertEquals(currentPlan.getRecurringBillingPeriod(), expBillingPeriod);

        final PlanPhase currentPhase = subscription.getCurrentPhase();
        assertNotNull(currentPhase);
        assertEquals(currentPhase.getPhaseType(), expPhase);
    }

    @Test(groups = "slow")
    public void testChangePlanBundleAlignEOTWithNoChargeThroughDate() {
        tChangePlanBundleAlignEOTWithNoChargeThroughDate("Shotgun", BillingPeriod.MONTHLY, PriceListSet.DEFAULT_PRICELIST_NAME, "Pistol", BillingPeriod.MONTHLY, PriceListSet.DEFAULT_PRICELIST_NAME);
    }

    private void tChangePlanBundleAlignEOTWithNoChargeThroughDate(final String fromProd, final BillingPeriod fromTerm, final String fromPlanSet,
                                                                  final String toProd, final BillingPeriod toTerm, final String toPlanSet) {
        try {
            // CREATE
            final DefaultSubscriptionBase subscription = testUtil.createSubscription(bundle, fromProd, fromTerm, fromPlanSet);

            // MOVE TO NEXT PHASE
            PlanPhase currentPhase = subscription.getCurrentPhase();
            testListener.pushExpectedEvent(NextEvent.PHASE);

            final Interval it = new Interval(clock.getUTCNow(), clock.getUTCNow().plusDays(31));
            clock.addDeltaFromReality(it.toDurationMillis());

            final DateTime futureNow = clock.getUTCNow();
            final DateTime nextExpectedPhaseChange = TestSubscriptionHelper.addDuration(subscription.getStartDate(), currentPhase.getDuration());
            assertTrue(futureNow.isAfter(nextExpectedPhaseChange));
            assertListenerStatus();

            // CHANGE PLAN
            testListener.pushExpectedEvent(NextEvent.CHANGE);
            subscription.changePlan(new PlanSpecifier(toProd, toTerm, toPlanSet), null, callContext);
            assertListenerStatus();

            // CHECK CHANGE PLAN
            currentPhase = subscription.getCurrentPhase();
            checkChangePlan(subscription, toProd, ProductCategory.BASE, toTerm, PhaseType.EVERGREEN);

            assertListenerStatus();
        } catch (SubscriptionBaseApiException e) {
            Assert.fail(e.getMessage());
        }
    }

    @Test(groups = "slow")
    public void testChangePlanBundleAlignEOTWithChargeThroughDate() throws SubscriptionBillingApiException, SubscriptionBaseApiException {
        testChangePlanBundleAlignEOTWithChargeThroughDate("Shotgun", BillingPeriod.ANNUAL, "gunclubDiscount", "Pistol", BillingPeriod.ANNUAL, "gunclubDiscount");
    }

    private void testChangePlanBundleAlignEOTWithChargeThroughDate(final String fromProd, final BillingPeriod fromTerm, final String fromPlanSet,
                                                                   final String toProd, final BillingPeriod toTerm, final String toPlanSet) throws SubscriptionBillingApiException, SubscriptionBaseApiException {
        // CREATE
        DefaultSubscriptionBase subscription = testUtil.createSubscription(bundle, fromProd, fromTerm, fromPlanSet);
        final PlanPhase trialPhase = subscription.getCurrentPhase();
        final DateTime expectedPhaseTrialChange = TestSubscriptionHelper.addDuration(subscription.getStartDate(), trialPhase.getDuration());
        assertEquals(trialPhase.getPhaseType(), PhaseType.TRIAL);

        // MOVE TO NEXT PHASE
        testListener.pushExpectedEvent(NextEvent.PHASE);
        Interval it = new Interval(clock.getUTCNow(), clock.getUTCNow().plusDays(31));
        clock.addDeltaFromReality(it.toDurationMillis());
        assertListenerStatus();
        PlanPhase currentPhase = subscription.getCurrentPhase();
        assertEquals(currentPhase.getPhaseType(), PhaseType.DISCOUNT);

        // SET CTD
        final Duration ctd = testUtil.getDurationMonth(1);
        final DateTime newChargedThroughDate = TestSubscriptionHelper.addDuration(expectedPhaseTrialChange, ctd);
        subscriptionInternalApi.setChargedThroughDate(subscription.getId(), newChargedThroughDate, internalCallContext);

        // RE READ SUBSCRIPTION + CHANGE PLAN
        subscription = (DefaultSubscriptionBase) subscriptionInternalApi.getSubscriptionFromId(subscription.getId(), internalCallContext);
        subscription.changePlan(new PlanSpecifier(toProd, toTerm, toPlanSet), null, callContext);
        assertListenerStatus();

        // CHECK CHANGE PLAN
        currentPhase = subscription.getCurrentPhase();
        checkChangePlan(subscription, fromProd, ProductCategory.BASE, fromTerm, PhaseType.DISCOUNT);

        // NEXT PHASE
        final DateTime nextExpectedPhaseChange = TestSubscriptionHelper.addDuration(expectedPhaseTrialChange, currentPhase.getDuration());
        testUtil.checkNextPhaseChange(subscription, 2, nextExpectedPhaseChange);

        // ALSO VERIFY PENDING CHANGE EVENT
        final List<SubscriptionBaseEvent> events = dao.getPendingEventsForSubscription(subscription.getId(), internalCallContext);
        assertTrue(events.get(0) instanceof ApiEvent);

        // MOVE TO EOT
        testListener.pushExpectedEvent(NextEvent.CHANGE);
        it = new Interval(clock.getUTCNow(), clock.getUTCNow().plusMonths(1));
        clock.addDeltaFromReality(it.toDurationMillis());
        assertListenerStatus();

        subscription = (DefaultSubscriptionBase) subscriptionInternalApi.getSubscriptionFromId(subscription.getId(), internalCallContext);
        currentPhase = subscription.getCurrentPhase();
        checkChangePlan(subscription, toProd, ProductCategory.BASE, toTerm, PhaseType.DISCOUNT);

        assertListenerStatus();
    }

    @Test(groups = "slow")
    public void testChangePlanBundleAlignIMM() throws SubscriptionBaseApiException {
        tChangePlanBundleAlignIMM("Shotgun", BillingPeriod.MONTHLY, PriceListSet.DEFAULT_PRICELIST_NAME, "Assault-Rifle", BillingPeriod.MONTHLY, PriceListSet.DEFAULT_PRICELIST_NAME);
    }

    private void tChangePlanBundleAlignIMM(final String fromProd, final BillingPeriod fromTerm, final String fromPlanSet,
                                           final String toProd, final BillingPeriod toTerm, final String toPlanSet) throws SubscriptionBaseApiException {
        final DefaultSubscriptionBase subscription = testUtil.createSubscription(bundle, fromProd, fromTerm, fromPlanSet);

        testListener.pushExpectedEvent(NextEvent.CHANGE);

        Interval it = new Interval(clock.getUTCNow(), clock.getUTCNow().plusDays(3));
        clock.addDeltaFromReality(it.toDurationMillis());

        // CHANGE PLAN IMM
        subscription.changePlan(new PlanSpecifier(toProd, toTerm, toPlanSet), null, callContext);
        checkChangePlan(subscription, toProd, ProductCategory.BASE, toTerm, PhaseType.TRIAL);

        assertListenerStatus();

        final PlanPhase currentPhase = subscription.getCurrentPhase();
        final DateTime nextExpectedPhaseChange = TestSubscriptionHelper.addDuration(subscription.getStartDate(), currentPhase.getDuration());
        testUtil.checkNextPhaseChange(subscription, 1, nextExpectedPhaseChange);

        // NEXT PHASE
        testListener.pushExpectedEvent(NextEvent.PHASE);
        it = new Interval(clock.getUTCNow(), clock.getUTCNow().plusDays(30));
        clock.addDeltaFromReality(it.toDurationMillis());
        final DateTime futureNow = clock.getUTCNow();

        assertTrue(futureNow.isAfter(nextExpectedPhaseChange));
        assertListenerStatus();
    }

    @Test(groups = "slow")
    public void testChangePlanChangePlanAlignEOTWithChargeThroughDate() throws SubscriptionBillingApiException, SubscriptionBaseApiException {
        tChangePlanChangePlanAlignEOTWithChargeThroughDate("Shotgun", BillingPeriod.ANNUAL, PriceListSet.DEFAULT_PRICELIST_NAME, "Assault-Rifle", BillingPeriod.ANNUAL, "rescue");
    }

    private void tChangePlanChangePlanAlignEOTWithChargeThroughDate(final String fromProd, final BillingPeriod fromTerm, final String fromPlanSet,
                                                                    final String toProd, final BillingPeriod toTerm, final String toPlanSet) throws SubscriptionBillingApiException, SubscriptionBaseApiException {
        DateTime currentTime = clock.getUTCNow();

        DefaultSubscriptionBase subscription = testUtil.createSubscription(bundle, fromProd, fromTerm, fromPlanSet);
        final PlanPhase trialPhase = subscription.getCurrentPhase();
        final DateTime expectedPhaseTrialChange = TestSubscriptionHelper.addDuration(subscription.getStartDate(), trialPhase.getDuration());
        assertEquals(trialPhase.getPhaseType(), PhaseType.TRIAL);

        // MOVE TO NEXT PHASE
        testListener.pushExpectedEvent(NextEvent.PHASE);
        currentTime = clock.getUTCNow();
        Interval it = new Interval(clock.getUTCNow(), clock.getUTCNow().plusDays(31));
        clock.addDeltaFromReality(it.toDurationMillis());
        currentTime = clock.getUTCNow();
        assertListenerStatus();

        // SET CTD
        final Duration ctd = testUtil.getDurationMonth(1);
        final DateTime newChargedThroughDate = TestSubscriptionHelper.addDuration(expectedPhaseTrialChange, ctd);
        subscriptionInternalApi.setChargedThroughDate(subscription.getId(), newChargedThroughDate, internalCallContext);

        // RE READ SUBSCRIPTION + CHECK CURRENT PHASE
        subscription = (DefaultSubscriptionBase) subscriptionInternalApi.getSubscriptionFromId(subscription.getId(), internalCallContext);
        PlanPhase currentPhase = subscription.getCurrentPhase();
        assertNotNull(currentPhase);
        assertEquals(currentPhase.getPhaseType(), PhaseType.EVERGREEN);

        // CHANGE PLAN
        currentTime = clock.getUTCNow();
        subscription.changePlan(new PlanSpecifier(toProd, toTerm, toPlanSet), null, callContext);

        checkChangePlan(subscription, fromProd, ProductCategory.BASE, fromTerm, PhaseType.EVERGREEN);

        // CHECK CHANGE DID NOT KICK IN YET
        assertListenerStatus();

        // MOVE TO AFTER CTD
        testListener.pushExpectedEvent(NextEvent.CHANGE);
        it = new Interval(clock.getUTCNow(), clock.getUTCNow().plusMonths(1));
        clock.addDeltaFromReality(it.toDurationMillis());
        currentTime = clock.getUTCNow();
        assertListenerStatus();

        // CHECK CORRECT PRODUCT, PHASE, PLAN SET
        final String currentProduct = subscription.getCurrentPlan().getProduct().getName();
        assertNotNull(currentProduct);
        assertEquals(currentProduct, toProd);
        currentPhase = subscription.getCurrentPhase();
        assertNotNull(currentPhase);
        assertEquals(currentPhase.getPhaseType(), PhaseType.DISCOUNT);

        // MOVE TIME ABOUT ONE MONTH BEFORE NEXT EXPECTED PHASE CHANGE
        it = new Interval(clock.getUTCNow(), clock.getUTCNow().plusMonths(11));
        clock.addDeltaFromReality(it.toDurationMillis());
        currentTime = clock.getUTCNow();
        assertListenerStatus();

        final DateTime nextExpectedPhaseChange = TestSubscriptionHelper.addDuration(newChargedThroughDate, currentPhase.getDuration());
        testUtil.checkNextPhaseChange(subscription, 1, nextExpectedPhaseChange);

        // MOVE TIME RIGHT AFTER NEXT EXPECTED PHASE CHANGE
        testListener.pushExpectedEvent(NextEvent.PHASE);
        it = new Interval(clock.getUTCNow(), clock.getUTCNow().plusMonths(1));
        clock.addDeltaFromReality(it.toDurationMillis());

        currentTime = clock.getUTCNow();
        assertListenerStatus();

        assertListenerStatus();
    }

    @Test(groups = "slow")
    public void testMultipleChangeLastIMM() throws SubscriptionBillingApiException, SubscriptionBaseApiException {
        DefaultSubscriptionBase subscription = testUtil.createSubscription(bundle, "Assault-Rifle", BillingPeriod.MONTHLY, "gunclubDiscount");
        final PlanPhase trialPhase = subscription.getCurrentPhase();
        assertEquals(trialPhase.getPhaseType(), PhaseType.TRIAL);

        // MOVE TO NEXT PHASE
        testListener.pushExpectedEvent(NextEvent.PHASE);
        final Interval it = new Interval(clock.getUTCNow(), clock.getUTCNow().plusDays(31));
        clock.addDeltaFromReality(it.toDurationMillis());

        assertListenerStatus();

        // SET CTD
        final List<Duration> durationList = new ArrayList<Duration>();
        durationList.add(trialPhase.getDuration());
        //durationList.add(subscription.getCurrentPhase().getDuration());
        final DateTime startDiscountPhase = TestSubscriptionHelper.addDuration(subscription.getStartDate(), durationList);
        final Duration ctd = testUtil.getDurationMonth(1);
        final DateTime newChargedThroughDate = TestSubscriptionHelper.addDuration(startDiscountPhase, ctd);
        subscriptionInternalApi.setChargedThroughDate(subscription.getId(), newChargedThroughDate, internalCallContext);
        subscription = (DefaultSubscriptionBase) subscriptionInternalApi.getSubscriptionFromId(subscription.getId(), internalCallContext);

        // CHANGE EOT
        subscription.changePlan(new PlanSpecifier("Pistol", BillingPeriod.MONTHLY, "gunclubDiscount"), null, callContext);
        assertListenerStatus();

        // CHANGE
        testListener.pushExpectedEvent(NextEvent.CHANGE);
        subscription.changePlan(new PlanSpecifier("Assault-Rifle", BillingPeriod.ANNUAL, "gunclubDiscount"), null, callContext);
        assertListenerStatus();

        final Plan currentPlan = subscription.getCurrentPlan();
        assertNotNull(currentPlan);
        assertEquals(currentPlan.getProduct().getName(), "Assault-Rifle");
        assertEquals(currentPlan.getProduct().getCategory(), ProductCategory.BASE);
        assertEquals(currentPlan.getRecurringBillingPeriod(), BillingPeriod.ANNUAL);

        final PlanPhase currentPhase = subscription.getCurrentPhase();
        assertNotNull(currentPhase);
        assertEquals(currentPhase.getPhaseType(), PhaseType.DISCOUNT);

        assertListenerStatus();
    }

    @Test(groups = "slow")
    public void testMultipleChangeLastEOT() throws SubscriptionBillingApiException, SubscriptionBaseApiException {
        DefaultSubscriptionBase subscription = testUtil.createSubscription(bundle, "Assault-Rifle", BillingPeriod.ANNUAL, "gunclubDiscount");
        final PlanPhase trialPhase = subscription.getCurrentPhase();
        assertEquals(trialPhase.getPhaseType(), PhaseType.TRIAL);

        testListener.pushExpectedEvent(NextEvent.PHASE);
        Interval it = new Interval(clock.getUTCNow(), clock.getUTCNow().plusDays(31));
        clock.addDeltaFromReality(it.toDurationMillis());
        assertListenerStatus();

        // SET CTD
        final List<Duration> durationList = new ArrayList<Duration>();
        durationList.add(trialPhase.getDuration());
        final DateTime startDiscountPhase = TestSubscriptionHelper.addDuration(subscription.getStartDate(), durationList);
        final Duration ctd = testUtil.getDurationMonth(1);
        final DateTime newChargedThroughDate = TestSubscriptionHelper.addDuration(startDiscountPhase, ctd);
        subscriptionInternalApi.setChargedThroughDate(subscription.getId(), newChargedThroughDate, internalCallContext);
        subscription = (DefaultSubscriptionBase) subscriptionInternalApi.getSubscriptionFromId(subscription.getId(), internalCallContext);

        // CHANGE EOT
        subscription.changePlan(new PlanSpecifier("Shotgun", BillingPeriod.MONTHLY, "gunclubDiscount"), null, callContext);
        assertListenerStatus();

        // CHANGE EOT
        subscription.changePlan(new PlanSpecifier("Pistol", BillingPeriod.ANNUAL, "gunclubDiscount"), null, callContext);
        assertListenerStatus();

        // CHECK NO CHANGE OCCURED YET
        Plan currentPlan = subscription.getCurrentPlan();
        assertNotNull(currentPlan);
        assertEquals(currentPlan.getProduct().getName(), "Assault-Rifle");
        assertEquals(currentPlan.getProduct().getCategory(), ProductCategory.BASE);
        assertEquals(currentPlan.getRecurringBillingPeriod(), BillingPeriod.ANNUAL);

        PlanPhase currentPhase = subscription.getCurrentPhase();
        assertNotNull(currentPhase);
        assertEquals(currentPhase.getPhaseType(), PhaseType.DISCOUNT);

        // ACTIVATE CHANGE BY MOVING AFTER CTD
        testListener.pushExpectedEvents(NextEvent.CHANGE);
        it = new Interval(clock.getUTCNow(), clock.getUTCNow().plusMonths(1));
        clock.addDeltaFromReality(it.toDurationMillis());
        assertListenerStatus();

        currentPlan = subscription.getCurrentPlan();
        assertNotNull(currentPlan);
        assertEquals(currentPlan.getProduct().getName(), "Pistol");
        assertEquals(currentPlan.getProduct().getCategory(), ProductCategory.BASE);
        assertEquals(currentPlan.getRecurringBillingPeriod(), BillingPeriod.ANNUAL);

        currentPhase = subscription.getCurrentPhase();
        assertNotNull(currentPhase);
        assertEquals(currentPhase.getPhaseType(), PhaseType.DISCOUNT);

        // MOVE TO NEXT PHASE
        testListener.pushExpectedEvent(NextEvent.PHASE);
        it = new Interval(clock.getUTCNow(), clock.getUTCNow().plusMonths(6));
        clock.addDeltaFromReality(it.toDurationMillis());
        assertListenerStatus();
        subscription = (DefaultSubscriptionBase) subscriptionInternalApi.getSubscriptionFromId(subscription.getId(), internalCallContext);

        currentPlan = subscription.getCurrentPlan();
        assertNotNull(currentPlan);
        assertEquals(currentPlan.getProduct().getName(), "Pistol");
        assertEquals(currentPlan.getProduct().getCategory(), ProductCategory.BASE);
        assertEquals(currentPlan.getRecurringBillingPeriod(), BillingPeriod.ANNUAL);

        currentPhase = subscription.getCurrentPhase();
        assertNotNull(currentPhase);
        assertEquals(currentPhase.getPhaseType(), PhaseType.EVERGREEN);

        assertListenerStatus();
    }

    @Test(groups = "slow")
    public void testCorrectPhaseAlignmentOnChange() throws SubscriptionBaseApiException {
        DefaultSubscriptionBase subscription = testUtil.createSubscription(bundle, "Shotgun", BillingPeriod.MONTHLY, PriceListSet.DEFAULT_PRICELIST_NAME);
        PlanPhase trialPhase = subscription.getCurrentPhase();
        assertEquals(trialPhase.getPhaseType(), PhaseType.TRIAL);

        // MOVE 2 DAYS AHEAD
        Interval it = new Interval(clock.getUTCNow(), clock.getUTCNow().plusDays(2));
        clock.addDeltaFromReality(it.toDurationMillis());

        // CHANGE IMMEDIATE TO A 3 PHASES PLAN
        testListener.pushExpectedEvent(NextEvent.CHANGE);
        subscription.changePlan(new PlanSpecifier("Assault-Rifle", BillingPeriod.ANNUAL, "gunclubDiscount"), null, callContext);
        assertListenerStatus();

        // CHECK EVERYTHING LOOKS CORRECT
        final Plan currentPlan = subscription.getCurrentPlan();
        assertNotNull(currentPlan);
        assertEquals(currentPlan.getProduct().getName(), "Assault-Rifle");
        assertEquals(currentPlan.getProduct().getCategory(), ProductCategory.BASE);
        assertEquals(currentPlan.getRecurringBillingPeriod(), BillingPeriod.ANNUAL);

        trialPhase = subscription.getCurrentPhase();
        assertEquals(trialPhase.getPhaseType(), PhaseType.TRIAL);

        // MOVE AFTER TRIAL PERIOD -> DISCOUNT
        testListener.pushExpectedEvent(NextEvent.PHASE);
        it = new Interval(clock.getUTCNow(), clock.getUTCNow().plusDays(30));
        clock.addDeltaFromReality(it.toDurationMillis());

        assertListenerStatus();

        trialPhase = subscription.getCurrentPhase();
        assertEquals(trialPhase.getPhaseType(), PhaseType.DISCOUNT);

        subscription = (DefaultSubscriptionBase) subscriptionInternalApi.getSubscriptionFromId(subscription.getId(), internalCallContext);

        final DateTime expectedNextPhaseDate = subscription.getStartDate().plusDays(30).plusMonths(6);
        final SubscriptionBaseTransition nextPhase = subscription.getPendingTransition();

        final DateTime nextPhaseEffectiveDate = nextPhase.getEffectiveTransitionTime();
        assertEquals(nextPhaseEffectiveDate, expectedNextPhaseDate);

        assertListenerStatus();
    }

    @Test(groups = "slow")
    public void testInvalidChangesAcrossProductTypes() throws SubscriptionBaseApiException {
        final String baseProduct = "Shotgun";
        final BillingPeriod baseTerm = BillingPeriod.MONTHLY;
        final String basePriceList = PriceListSet.DEFAULT_PRICELIST_NAME;

        // CREATE BP
        final DefaultSubscriptionBase baseSubscription = testUtil.createSubscription(bundle, baseProduct, baseTerm, basePriceList);

        // MOVE CLOCK 14 DAYS LATER
        Interval it = new Interval(clock.getUTCNow(), clock.getUTCNow().plusDays(14));
        clock.addDeltaFromReality(it.toDurationMillis());

        // Create AO
        final String aoProduct = "Laser-Scope";
        final BillingPeriod aoTerm = BillingPeriod.MONTHLY;
        final String aoPriceList = PriceListSet.DEFAULT_PRICELIST_NAME;
        DefaultSubscriptionBase aoSubscription = testUtil.createSubscription(bundle, aoProduct, aoTerm, aoPriceList);

        try {
            aoSubscription.changePlanWithDate(new PlanSpecifier(baseProduct, baseTerm, basePriceList), null, clock.getUTCNow(), callContext);
            Assert.fail("Should not allow plan change across product type");
        } catch (final SubscriptionBaseApiException e) {
            Assert.assertEquals(e.getCode(), ErrorCode.SUB_CHANGE_INVALID.getCode());
        }
    }


    @Test(groups = "slow")
    public void testChangePlanOnPendingSubscription() throws SubscriptionBaseApiException {

        final String baseProduct = "Shotgun";
        final BillingPeriod baseTerm = BillingPeriod.MONTHLY;
        final String basePriceList = PriceListSet.DEFAULT_PRICELIST_NAME;

        final DateTime startDate = clock.getUTCNow().plusDays(5);

        final DefaultSubscriptionBase subscription = testUtil.createSubscription(bundle, baseProduct, baseTerm, basePriceList, startDate);
        assertEquals(subscription.getState(), Entitlement.EntitlementState.PENDING);
        assertEquals(subscription.getStartDate().compareTo(startDate), 0);

        final PlanPhaseSpecifier spec = new PlanPhaseSpecifier("Pistol", baseTerm, basePriceList, null);


        // First try with default api (no date -> IMM) => Call should fail because subscription is PENDING
        final DryRunArguments dryRunArguments1  = testUtil.createDryRunArguments(subscription.getId(), subscription.getBundleId(), spec, null, SubscriptionEventType.CHANGE, null);
        final List<SubscriptionBase> result1 = subscriptionInternalApi.getSubscriptionsForBundle(bundle.getId(), dryRunArguments1, internalCallContext);

        // Check we are seeing the right PENDING transition (pistol-monthly), not the START but the CHANGE on the same date
        assertEquals(((DefaultSubscriptionBase) result1.get(0)).getCurrentOrPendingPlan().getName(), "pistol-monthly");
        assertEquals(((DefaultSubscriptionBase) result1.get(0)).getPendingTransition().getTransitionType(), SubscriptionBaseTransitionType.CREATE);

        // Second try with date prior to startDate => Call should fail because subscription is PENDING
        try {
            final DryRunArguments dryRunArguments2  = testUtil.createDryRunArguments(subscription.getId(), subscription.getBundleId(), spec, new LocalDate(startDate.minusDays(1)), SubscriptionEventType.CHANGE, null);
            subscriptionInternalApi.getSubscriptionsForBundle(bundle.getId(), dryRunArguments2, internalCallContext);
            fail("Change plan should have failed : subscription PENDING");
        } catch (final SubscriptionBaseApiException e) {
            assertEquals(e.getCode(), ErrorCode.SUB_CHANGE_NON_ACTIVE.getCode());
        }
        try {
            subscription.changePlanWithDate(spec, null, startDate.minusDays(1), callContext);
            fail("Change plan should have failed : subscription PENDING");
        } catch (final SubscriptionBaseApiException e) {
            assertEquals(e.getCode(), ErrorCode.SUB_INVALID_REQUESTED_DATE.getCode());
        }

        // Third try with date equals to startDate  Call should succeed, but no event because action in future
        final DryRunArguments dryRunArguments3  = testUtil.createDryRunArguments(subscription.getId(), subscription.getBundleId(), spec, internalCallContext.toLocalDate(startDate), SubscriptionEventType.CHANGE, null);
        final List<SubscriptionBase> result2 = subscriptionInternalApi.getSubscriptionsForBundle(bundle.getId(), dryRunArguments3, internalCallContext);
        // Check we are seeing the right PENDING transition (pistol-monthly), not the START but the CHANGE on the same date
        assertEquals(((DefaultSubscriptionBase) result2.get(0)).getCurrentOrPendingPlan().getName(), "pistol-monthly");


        // To spice up the test, we insert manually an additional CHANGE event on the exact same dateas the CREATE to verify that code will also invalidate such event when doing the changePlanXX

        final SubscriptionEventModelDao event = new SubscriptionEventModelDao(subscription.getEvents().get(0));
        event.setId(UUID.randomUUID());
        event.setUserType(ApiEventType.CHANGE);
        event.setPlanName("blowdart-monthly");
        event.setPhaseName("blowdart-monthly-trial");
        dbi.withHandle(new HandleCallback<Void>() {
            @Override
            public Void withHandle(Handle handle) throws Exception {
                final SubscriptionEventSqlDao sqlDao = handle.attach(SubscriptionEventSqlDao.class);
                sqlDao.create(event, internalCallContext);
                return null;
            }
        });

        final DefaultSubscriptionBase refreshed1 =  (DefaultSubscriptionBase) subscriptionInternalApi.getSubscriptionFromId(subscription.getId(), internalCallContext);
        assertEquals(refreshed1.getEvents().size(), subscription.getEvents().size() + 1);

        subscription.changePlanWithDate(spec, null, startDate, callContext);
        assertListenerStatus();

        // Move clock to startDate
        testListener.pushExpectedEvents(NextEvent.CREATE);
        clock.addDays(5);
        assertListenerStatus();

        final DefaultSubscriptionBase subscription2 = (DefaultSubscriptionBase) subscriptionInternalApi.getSubscriptionFromId(subscription.getId(), internalCallContext);
        assertEquals(subscription2.getStartDate().compareTo(startDate), 0);
        assertEquals(subscription2.getState(), Entitlement.EntitlementState.ACTIVE);
        assertEquals(subscription2.getCurrentPlan().getProduct().getName(), "Pistol");
        // Same original # active events
        assertEquals(subscription2.getEvents().size(), subscription.getEvents().size());
    }

}
